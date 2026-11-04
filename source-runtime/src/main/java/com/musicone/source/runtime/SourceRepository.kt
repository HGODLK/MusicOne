package com.musicone.source.runtime

import android.content.Context
import android.util.AtomicFile
import com.musicone.source.sdk.SourceHost
import com.musicone.source.sdk.SourceKind
import java.io.File
import java.util.UUID

class SourceRepository(context: Context, private val host: SourceHost) {
    private val root = File(context.filesDir, "source-packs").apply { mkdirs() }
    private val verifier = SourceArchiveVerifier(hostCompatibility = run {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        SourceHostCompatibility(code, info.versionName ?: "未知")
    })
    private val packages = SourcePackageStore(root)
    private val loader = SourceLoader(context)
    private val live = mutableMapOf<String, SourceInstance>()
    private val stateFile = AtomicFile(File(root, "registry.json"))
    val startupErrors = mutableListOf<String>()
    private var registryReadOnly = false
    private var started = false
    private val states = readStates()

    init { restoreDescriptorMetadata() }

    @Synchronized fun installed(): List<InstalledSource> = states.values.toList()
    @Synchronized fun running(id: String): SourceInstance? = live[id]

    @Synchronized fun importPackage(input: File, allowDowngrade: Boolean = true): ImportDecision {
        check(!registryReadOnly) { "音源注册表损坏，已保留原文件；恢复前禁止覆盖安装记录" }
        val staging = File(root, "staging-${UUID.randomUUID()}.${input.extension}")
        try {
            input.copyTo(staging)
            check(staging.setReadOnly())
            val archive = verifier.verify(staging, input.name)
            val descriptor = archive.manifest.descriptor
            val old = states[descriptor.id]
            check(old?.pendingRemoval != true) { "音源正等待删除，请先撤销删除" }
            // 删除后仍校验保留的签名身份，但允许主动重导入原版本恢复关联。
            val previous = old?.let { PackageIdentity(it.id, it.signerSha256,
                if (it.removed) -1 else it.pendingVersion ?: it.activeVersion ?: -1) }
            val selected = old?.takeUnless { it.removed }?.let { it.pendingPackage ?: it.activePackage }
            val identical = selected != null && packages.sameContent(descriptor.id, selected, staging)
            val decision = SourceImportPolicy.decide(previous,
                PackageIdentity(descriptor.id, archive.signerSha256, descriptor.versionCode), allowDowngrade, identical)
            if (decision == ImportDecision.ALREADY_INSTALLED) return decision
            val sandbox = File(root, "validation-${UUID.randomUUID()}")
            try { loader.load(archive, sandbox, host, true).use { } }
            finally { sandbox.deleteRecursively(); File(sandbox.parentFile, "${sandbox.name}-cache").deleteRecursively() }
            val target = packages.retain(descriptor.id, descriptor.versionCode, descriptor.kind.extension, staging, old)
            val updated = (old ?: InstalledSource(descriptor.id, archive.signerSha256, null, null, null, false, null))
                .withPending(target, descriptor)
            states[descriptor.id] = updated
            try { persist() } catch (failure: Throwable) {
                if (old == null) states.remove(descriptor.id) else states[descriptor.id] = old
                throw failure
            }
            return decision
        } finally { staging.delete() }
    }

    // 只能在应用启动时调用。导入不调用此方法，因此不会热切换正在播放的旧实例。
    @Synchronized fun startAfterProcessRestart(): Map<String, SourceInstance> {
        check(!started && live.isEmpty()) { "音源启动只能执行一次" }
        started = true
        states.keys.toList().forEach { id ->
            val old = states.getValue(id).let {
                if (registryReadOnly) it.copy(pendingVersion = null, pendingInstallId = null) else it
            }
            if (old.pendingRemoval || (old.removed && old.kind == SourceKind.MAIN)) {
                if (!registryReadOnly) {
                    try {
                        states[id] = completeSourceRemoval(root, old)
                        persist()
                    } catch (failure: Exception) {
                        states[id] = old.copy(error = "音源删除未完成：${failure.message}")
                        startupErrors += "${old.name ?: id}：音源删除未完成"
                        persist()
                    }
                }
                return@forEach
            }
            if (old.removed) return@forEach
            val candidate = old.pendingPackage ?: old.activePackage ?: return@forEach
            val transaction = ActivationTransaction(File(root, "activation-backup/$id"), dataFile(id))
            var recoveredTransaction = false
            try {
                transaction.recover(old.activeVersion, old.pendingVersion, old.activeInstallId, old.pendingInstallId)
                recoveredTransaction = true
                if (old.pendingVersion != null) transaction.begin(candidate.versionCode, candidate.installId)
                try { live[id] = loadVersion(old, candidate) }
                catch (failure: Throwable) {
                    transaction.restore()
                    throw failure
                }
                val descriptor = live.getValue(id).entry.descriptor
                states[id] = old.activated(candidate, descriptor)
                persist()
                transaction.finish()
                packages.cleanup(states.getValue(id))
            } catch (failure: Throwable) {
                // 新包尚未成为唯一恢复点；失败时保留旧包和对应数据快照。
                live.remove(id)?.let { runCatching { it.close() } }
                val restored = recoveredTransaction && runCatching { transaction.restore() }.isSuccess
                if (!restored) {
                    states[id] = old.copy(error = "初始化数据恢复未完成：${failure.message}")
                    persist()
                    return@forEach
                }
                val fallback = old.activePackage?.takeIf { it != candidate }
                val recovered = fallback?.let { runCatching { loadVersion(old, it) }.getOrNull() }
                if (fallback != null && recovered == null) {
                    val restoredFallback = runCatching { transaction.restore() }.isSuccess
                    if (!restoredFallback) {
                        states[id] = old.copy(error = "旧版本初始化失败且数据恢复未完成")
                        persist()
                        return@forEach
                    }
                }
                if (recovered != null) live[id] = recovered
                states[id] = old.copy(pendingVersion = null, pendingInstallId = null,
                    pendingVersionName = null, error = failure.message ?: failure.javaClass.simpleName)
                persist()
                transaction.finish()
            }
        }
        persist()
        return live.toMap()
    }

    @Synchronized fun requestRollback(id: String) {
        check(!registryReadOnly) { "音源注册表损坏，暂不可修改" }
        val old = states.getValue(id)
        check(!old.removed && !old.pendingRemoval && old.rollbackVersion != null) { "没有可用回退版本" }
        val target = requireNotNull(old.rollbackPackage)
        val descriptor = verifiedVersion(old, target).manifest.descriptor
        states[id] = old.withPending(target, descriptor)
        try { persist() } catch (failure: Throwable) { states[id] = old; throw failure }
    }

    /** 本轮立即停用，包与账号保留到下一次进程启动，期间可撤销。 */
    @Synchronized fun scheduleRemoval(id: String) {
        check(!registryReadOnly) { "音源注册表损坏，暂不可修改" }
        val old = states.getValue(id)
        states[id] = old.scheduledForRemoval()
        try { persist() } catch (failure: Throwable) { states[id] = old; throw failure }
        live.remove(id)?.let { runCatching { it.close() } }
    }

    /** 撤销只恢复本次进程原运行版本，不提前应用待更新或待回退版本。 */
    @Synchronized fun undoRemoval(id: String): SourceInstance? {
        check(!registryReadOnly) { "音源注册表损坏，暂不可修改" }
        val old = states.getValue(id)
        check(old.pendingRemoval && !old.removed) { "音源没有待删除操作" }
        val instance = old.activePackage?.let { loadVersion(old, it) }
        states[id] = old.removalUndone()
        try { persist() } catch (failure: Throwable) {
            states[id] = old
            instance?.let { runCatching { it.close() } }
            throw failure
        }
        if (instance != null) live[id] = instance
        return instance
    }

    @Synchronized fun remove(id: String) {
        check(!registryReadOnly) { "音源注册表损坏，暂不可修改" }
        val old = states.getValue(id)
        // 宿主先完成当前播放来源确认与停止，关闭租约后迟到回调不能写宿主。
        live.remove(id)?.let { runCatching { it.close() } }
        states[id] = old.withoutPackages()
        persist()
        File(root, "packages/$id").deleteRecursively()
        File(root, "activation-backup/$id").deleteRecursively()
    }

    @Synchronized fun installPrebundledOnce(files: List<File>, onVerified: (VerifiedSourceArchive) -> Unit = {}) {
        if (registryReadOnly) return
        files.forEach { file ->
            try {
                val archive = verifier.verify(file)
                onVerified(archive)
                val descriptor = archive.manifest.descriptor
                val previous = states[descriptor.id]
                if (previous != null && previous.signerSha256 == archive.signerSha256 &&
                    (previous.kind == null || previous.name == null)) {
                    states[descriptor.id] = previous.copy(kind = descriptor.kind, name = descriptor.name,
                        versionName = previous.versionName ?: descriptor.versionName.takeIf { previous.activeVersion == descriptor.versionCode },
                        pendingVersionName = previous.pendingVersionName ?: descriptor.versionName.takeIf { previous.pendingVersion == descriptor.versionCode })
                    persist()
                }
                if (shouldInstallBundledSource(states[descriptor.id], archive.signerSha256, descriptor.versionCode)) {
                    importPackage(file)
                }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // 分发中的单个包失败不影响其他已验证音源和本地音乐入口。
                startupErrors += "${file.name}：${failure.message ?: "预装音源初始化失败"}"
            }
        }
    }

    @Synchronized fun close() { live.values.forEach { runCatching { it.close() } }; live.clear() }

    private fun verifiedVersion(state: InstalledSource, reference: SourceInstallRef): VerifiedSourceArchive {
        val archive = verifier.verify(packages.file(state.id, reference))
        require(archive.signerSha256 == state.signerSha256 && archive.manifest.descriptor.id == state.id &&
            archive.manifest.descriptor.versionCode == reference.versionCode) { "已安装包身份变化" }
        return archive
    }

    private fun loadVersion(state: InstalledSource, reference: SourceInstallRef): SourceInstance =
        loader.load(verifiedVersion(state, reference), dataFile(state.id), host, false)

    /** 旧注册表只从重新验签的现存包补充展示字段，不执行第三方入口。 */
    private fun restoreDescriptorMetadata() {
        var changed = false
        states.keys.toList().forEach { id ->
            val old = states.getValue(id)
            if (old.kind != null && old.name != null && old.versionName != null &&
                (old.pendingVersion == null || old.pendingVersionName != null)) return@forEach
            val active = old.activePackage?.let { runCatching { verifiedVersion(old, it).manifest.descriptor }.getOrNull() }
            val pending = old.pendingPackage?.let { runCatching { verifiedVersion(old, it).manifest.descriptor }.getOrNull() }
            val descriptor = active ?: pending ?: old.rollbackPackage?.let {
                runCatching { verifiedVersion(old, it).manifest.descriptor }.getOrNull()
            } ?: return@forEach
            states[id] = old.copy(kind = descriptor.kind, name = descriptor.name,
                versionName = active?.versionName ?: old.versionName,
                pendingVersionName = pending?.versionName ?: old.pendingVersionName)
            changed = true
        }
        if (changed) persist()
    }

    private fun dataFile(id: String) = File(root, "data/$id")

    private fun readStates(): MutableMap<String, InstalledSource> {
        if (!stateFile.baseFile.exists() && !File(stateFile.baseFile.path + ".bak").exists()) return linkedMapOf()
        val result = SourceRegistryReader.read { stateFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
        registryReadOnly = result.errors.isNotEmpty()
        startupErrors.addAll(result.errors)
        return result.states
    }
    private fun persist() {
        if (registryReadOnly) return
        val json = SourceRegistryWriter.write(states.values)
        val output = stateFile.startWrite()
        try { output.write(json.toByteArray()); stateFile.finishWrite(output) }
        catch (failure: Throwable) { stateFile.failWrite(output); throw failure }
    }
}
