package com.musicone.source.runtime

import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import dalvik.system.DexClassLoader
import java.io.File

class SourceInstance internal constructor(
    val archive: VerifiedSourceArchive,
    val entry: SourceEntry,
    val context: Context,
    private val lease: SourceHostLease
) : AutoCloseable {
    @Composable fun Content(route: SourceRoute = SourceRoute()) {
        SourceResourceScope(context) { entry.Content(route) }
    }
    @Composable fun AuthenticationContent() {
        SourceResourceScope(context) { entry.authenticationPage?.Content() ?: entry.Content(SourceRoute("authentication")) }
    }
    @Composable fun SettingsContent() {
        SourceResourceScope(context) { entry.settings?.Content() ?: entry.Content(SourceRoute("settings")) }
    }
    @Composable fun SupplementalPage(pageContext: SourceSupplementalPageContext) {
        SourceResourceScope(context) { requireNotNull(entry.supplementalPageProviderOrNull()).SupplementalPage(pageContext) }
    }
    @Composable fun PlayerUi(playerContext: SourcePlayerUiContext) {
        SourceResourceScope(context) { requireNotNull(entry.playerUiProviderOrNull()).Player(playerContext) }
    }
    fun onBack(): Boolean = entry.onBack()
    suspend fun <T> withOperation(block: suspend () -> T): T = lease.operations.call(block = block)
    override fun close() { lease.invalidate(); entry.close() }
}

internal class SourceHostLease(private val host: SourceHost, private val validationOnly: Boolean,
    private val descriptor: SourceDescriptor) : SourceHost {
    @Volatile var active = true
    private val tasks = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    internal val operations = SourceOperationLease { checkAccess() }
    private val capabilities = SourceHostCapabilitiesLease(host, descriptor, operations)
    override val library get() = capabilities.library
    override val cache get() = capabilities.cache
    override val ui get() = capabilities.ui
    override val contentUi: SourceContentUi? = host.contentUi?.let { content -> object : SourceContentUi by content {
        private val publicUi = SourcePublicUiLease(content, descriptor.id, operations)
        override val pages get() = publicUi.pages
        override val components get() = publicUi.components
        override fun openCollection(collection: SourceCollection, onClosed: () -> Unit) = operations.dispatch(descriptor.id) {
            require(collection.identity.sourceId == descriptor.id) { "不能打开其他音源的歌单" }
            content.openCollection(collection) { if (active && !validationOnly) operations.dispatch(descriptor.id, onClosed) }
        }
    } }
    override val playerState get() = if (active && !validationOnly) host.playerState else null
    override val reduceMotion get() = host.reduceMotion
    fun invalidate() { operations.invalidate { active = false }; tasks.forEach { runCatching { it() } }; tasks.clear() }
    private fun checkAccess() { check(active && !validationOnly) { "运行实例已失效或正在隔离验证" } }
    override fun play(tracks: List<SourceTrack>, startIndex: Int) = operations.dispatch { checkTracks(tracks); host.play(tracks, startIndex) }
    override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = operations.dispatch { checkTracks(tracks); host.importTracks(tracks, destinationId) }
    override fun exitSource() = operations.dispatch(descriptor.id) { host.exitSource() }
    override fun reportError(message: String) { if (active && !validationOnly) operations.dispatch { host.reportError(message) } }
    override fun prepareSourceStorage(sourceId: String, context: Context) { checkAccess(); require(sourceId == descriptor.id); host.prepareSourceStorage(sourceId, context) }
    override fun playDetailed(tracks: List<SourceTrackDetails>, startIndex: Int) = operations.dispatch { checkTracks(tracks.map { it.track }); host.playDetailed(tracks, startIndex) }
    override fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean) = operations.dispatch { checkTracks(tracks.map { it.track }); host.enqueueDetailed(tracks, playNext) }
    override fun playRecommendation(track: SourceTrackDetails) = operations.dispatch { checkTracks(listOf(track.track)); host.playRecommendation(track) }
    private fun checkTracks(tracks: List<SourceTrack>) { require(tracks.all { it.identity.sourceId == descriptor.id }) { "不能跨音源提交曲目" } }
    override fun registerTask(cancel: () -> Unit): AutoCloseable {
        check(active) { "运行实例已失效" }
        tasks.add(cancel)
        if (!active) { tasks.remove(cancel); cancel() }
        return AutoCloseable { tasks.remove(cancel) }
    }
}

class SourceLoader(private val hostContext: Context) {
    fun load(archive: VerifiedSourceArchive, dataDirectory: File, host: SourceHost, validationOnly: Boolean): SourceInstance {
        // Android 14 以上只允许从只读文件加载外部代码；安装和校验完成后不再修改原文件。
        check(archive.file.setReadOnly()) { "无法将外部代码设为只读" }
        val loader = DexClassLoader(archive.file.absolutePath, hostContext.codeCacheDir.absolutePath,
            null, SourceEntry::class.java.classLoader)
        val applicationInfo = ApplicationInfo().apply {
            packageName = archive.manifest.descriptor.id
            sourceDir = archive.file.absolutePath
            publicSourceDir = sourceDir
        }
        val resources = SourceResources.load(hostContext, archive.file)
        val packageTheme = resources.newTheme().apply { setTo(hostContext.theme) }
        val preferences = mutableMapOf<String, SourcePreferences>()
        val context = object : ContextWrapper(hostContext) {
            override fun getResources(): Resources = resources
            override fun getTheme(): Resources.Theme = packageTheme
            override fun setTheme(resid: Int) { packageTheme.applyStyle(resid, true) }
            override fun getAssets(): AssetManager = resources.assets
            override fun getClassLoader(): ClassLoader = loader
            override fun getPackageName(): String = applicationInfo.packageName
            override fun getApplicationInfo(): ApplicationInfo = applicationInfo
            override fun getApplicationContext(): Context = this
            // Android 12L 及以下默认转给 getApplicationContext()，返回自身会无限递归，WebView 挂载时即栈溢出。
            override fun registerComponentCallbacks(callback: ComponentCallbacks) = hostContext.registerComponentCallbacks(callback)
            override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = hostContext.unregisterComponentCallbacks(callback)
            override fun getFilesDir(): File = dataDirectory
            override fun getCacheDir(): File = File(dataDirectory.parentFile, "${dataDirectory.name}-cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(dataDirectory, "no-backup").apply { mkdirs() }
            override fun getFileStreamPath(name: String): File = scopedFile(name)
            override fun openFileInput(name: String): java.io.FileInputStream = scopedFile(name).inputStream()
            override fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream =
                java.io.FileOutputStream(scopedFile(name), mode and Context.MODE_APPEND != 0)
            override fun deleteFile(name: String): Boolean = scopedFile(name).delete()
            override fun fileList(): Array<String> = dataDirectory.list() ?: emptyArray()
            private fun scopedFile(name: String): File {
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..")
                dataDirectory.mkdirs()
                return File(dataDirectory, name)
            }
            override fun getDir(name: String, mode: Int): File {
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..")
                return File(dataDirectory, name).apply { mkdirs() }
            }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..")
                return preferences.getOrPut(name) { SourcePreferences(File(dataDirectory, "preferences/$name.json")) }
            }
        }
        val entryClass = loader.loadClass(archive.manifest.entryClass)
        require(entryClass.classLoader === loader) { "音源入口必须来自外部包" }
        val entry = entryClass.getDeclaredConstructor().newInstance() as? SourceEntry ?: error("包入口未实现公开 SDK")
        require(entry.descriptor == archive.manifest.descriptor) { "${archive.manifest.descriptor.id}：入口与签名清单声明不一致" }
        val lease = SourceHostLease(host, validationOnly, entry.descriptor)
        val cache = File(dataDirectory.parentFile, "${dataDirectory.name}-cache").apply { mkdirs() }
        try {
            dataDirectory.mkdirs()
            if (!validationOnly) lease.prepareSourceStorage(entry.descriptor.id, context)
            entry.initialize(SourceEnvironment(context, dataDirectory, cache, lease, validationOnly))
            // 能力服务可依赖初始化后的私有存储与会话，先初始化再核验声明。
            SourceCapabilityValidator.check(entry)
            SourceOptionalContractValidator.check(entry)
            // 真实启动提前读取账号，将共享 ABI 或账号读取故障留在加载失败边界内。
            if (!validationOnly) entry.authentication?.currentAccount()
            lease.operations.accountVersion = {
                val account = entry.authentication?.currentAccount()?.let { Triple(it.status, it.account?.id, it.revision) }
                val supplemental = entry.supplementalPageProviderOrNull()
                if (supplemental == null) account else account to supplemental.publicSubscriptionIdentity()
            }
        } catch (failure: Throwable) {
            lease.invalidate()
            runCatching { entry.close() }
            throw failure
        }
        return SourceInstance(archive, SourceGuardedEntry(entry, lease.operations), context, lease)
    }
}
