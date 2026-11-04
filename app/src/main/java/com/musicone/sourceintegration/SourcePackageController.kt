package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource
import com.musicone.source.runtime.ImportDecision
import com.musicone.source.runtime.InstalledSource
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.runtime.SourceRepository
import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** 一个应用进程只激活一次；导入、回退只修改待重启版本。 */
internal class SourcePackageController private constructor(private val context: Context) {
    private val mutableInstalled = MutableStateFlow<List<InstalledSource>>(emptyList())
    val installed = mutableInstalled.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val trustedSigners = mutableMapOf<String, String>()
    private val migration = LegacySourceDataMigration(context, trustedSigners)
    private var actions: SourceHost? = null
    private var playingSource: () -> String? = { null }
    private var stopSourcePlayback: () -> Unit = {}
    private var notifyRemoval: (String) -> Unit = {}
    @Volatile private var activeInstances: Map<String, SourceInstance> = emptyMap()
    private val startupTask = SourceStartup(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { initialize() }
    val startup = startupTask.state

    private val repository: SourceRepository by lazy { SourceRepository(context, object : SourceHost {
        override val library get() = actions?.library
        override val cache get() = actions?.cache
        override val ui get() = actions?.ui
        override val contentUi get() = actions?.contentUi
        override val playerState get() = actions?.playerState
        override val reduceMotion get() = actions?.reduceMotion ?: false
        override fun play(tracks: List<SourceTrack>, startIndex: Int) = requireActions().play(tracks, startIndex)
        override fun playDetailed(tracks: List<SourceTrackDetails>, startIndex: Int) = requireActions().playDetailed(tracks, startIndex)
        override fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean) = requireActions().enqueueDetailed(tracks, playNext)
        override fun playRecommendation(track: SourceTrackDetails) = requireActions().playRecommendation(track)
        override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = requireActions().importTracks(tracks, destinationId)
        override fun exitSource() = requireActions().exitSource()
        override fun reportError(message: String) { mutableMessage.value = message; actions?.reportError(message) }
        override fun prepareSourceStorage(sourceId: String, context: Context) {
            val identity = repository.installed().firstOrNull { it.id == sourceId } ?: return
            migration.migrate(sourceId, identity.signerSha256, context)
        }
    }) }

    fun bind(host: SourceHost, currentPlayingSource: () -> String?, stopPlayback: () -> Unit, onRemoved: (String) -> Unit) {
        actions = host
        playingSource = currentPlayingSource
        stopSourcePlayback = stopPlayback
        notifyRemoval = onRemoved
    }

    fun start() = startupTask.start()

    suspend fun awaitStartup() {
        SourceApplicationHost.get(context).start()
        startupTask.await()
        startup.value.error?.let { throw IllegalStateException(it) }
    }

    private fun initialize() {
        val distribution = File(context.cacheDir, "prebundled-sources").apply { mkdirs() }
        val packages = context.assets.list("source-packs").orEmpty()
            .filter { it.endsWith(".mscm") || it.endsWith(".mscp") }
            .map { name ->
                val copy = File(distribution, name)
                if (copy.exists()) check(copy.delete()) { "无法刷新预装分发副本" }
                context.assets.open("source-packs/$name").use { input -> copy.outputStream().use { input.copyTo(it) } }
                copy
            }
        repository.installPrebundledOnce(packages) { archive ->
            trustedSigners[archive.manifest.descriptor.id] = archive.signerSha256
            MusicSource.register(archive.manifest.descriptor)
        }
        // 先从可信清单补齐旧记录的类型；待删除期间保留账号，下一次启动才清迁移副本。
        repository.installed().filter { it.kind == SourceKind.MAIN && (it.pendingRemoval || it.removed) }
            .forEach { clearRemovedSourceLegacyAccount(context, it.id) }
        repository.startAfterProcessRestart().values.forEach { MusicSource.register(it.entry.descriptor) }
        repository.startupErrors.firstOrNull()?.let { mutableMessage.value = it }
        // 保留删除记录的音源不参与主入口选择，缺包曲目仍使用原稳定身份。
        val selection = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val selected = selection.getString("selected_source", null)?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
        if (selected != null && selected != MusicSource.LOCAL && repository.running(selected.id) == null) {
            selection.edit().putString("selected_source", MusicSource.LOCAL.name).apply()
        }
        refresh()
    }

    // 页面只读已经发布的实例，不能争抢安装、验签和加载过程中持有的仓库锁。
    fun running(id: String): SourceInstance? = activeInstances[id]
    fun available(kind: SourceKind): List<SourceInstance> = activeInstances.values
        .filter { it.entry.descriptor.kind == kind }

    @Synchronized fun importPackage(file: File, allowDowngrade: Boolean = true): ImportDecision {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        val result = repository.importPackage(file, allowDowngrade)
        refresh()
        mutableMessage.value = if (result == ImportDecision.ALREADY_INSTALLED) "该版本已安装" else "音源已保存，重启应用后生效"
        return result
    }

    fun isPlayingSource(id: String): Boolean = playingSource() == id

    @Synchronized fun remove(id: String, confirmedPlayingRemoval: Boolean = false) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        require(id != MusicSource.LOCAL.id) { "本地音源始终内置" }
        if (isPlayingSource(id)) {
            require(confirmedPlayingRemoval) { "该音源正在播放，须确认停止播放后移除" }
            stopSourcePlayback()
        }
        // 先撤下可读实例，再关闭租约；通知观察者时必须已经发布新快照。
        val main = repository.installed().first { it.id == id }.kind == SourceKind.MAIN
        activeInstances = activeInstances - id
        try { if (main) repository.scheduleRemoval(id) else repository.remove(id) }
        catch (failure: Exception) { refresh(); throw failure }
        val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val selected = preferences.getString("selected_source", null)?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
        if (selected?.id == id) preferences.edit().putString("selected_source", MusicSource.LOCAL.name).apply()
        refresh()
        notifyRemoval(id)
        mutableMessage.value = if (main) "音源已停用，可撤销；下次启动时删除音源与登录数据，歌曲资料保留"
            else "音源已移除，账号与资料已保留"
    }

    @Synchronized fun undoRemoval(id: String) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        repository.undoRemoval(id)?.entry?.descriptor?.let(MusicSource::register)
        refresh()
        mutableMessage.value = "已撤销移除，音源与账号已恢复"
    }

    @Synchronized fun rollback(id: String) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        repository.requestRollback(id)
        refresh()
        mutableMessage.value = "回退已安排，重启应用后生效"
    }

    private fun refresh() {
        val snapshot = repository.installed()
        activeInstances = snapshot.mapNotNull { state -> repository.running(state.id)?.let { state.id to it } }.toMap()
        mutableInstalled.value = snapshot
    }
    private fun requireActions(): SourceHost = checkNotNull(actions) { "宿主界面尚未就绪" }

    companion object {
        @Volatile private var instance: SourcePackageController? = null
        fun current(): SourcePackageController? = instance
        fun get(context: Context): SourcePackageController = instance ?: synchronized(this) {
            instance ?: SourcePackageController(context.applicationContext).also { instance = it }
        }
    }
}
