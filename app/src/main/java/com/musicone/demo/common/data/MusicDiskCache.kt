package com.musicone.demo

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class CacheUsage(val ordinary: Long = 0, val protected: Long = 0)

/** 普通缓存和收藏音频共用索引；收藏保护标记独立持久化，不占普通配额。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicDiskCache private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("music_cache", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "managed_cache").apply { mkdirs() }
    private val auxiliary = File(root, "images_and_feed").apply { mkdirs() }
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = kotlinx.coroutines.sync.Mutex()
    private val mutableAudioRevision = MutableStateFlow(0L)
    val audioRevision = mutableAudioRevision.asStateFlow()
    @Volatile private var protectedTracks = preferences.getStringSet("protected", emptySet()).orEmpty().toSet()
    @Volatile private var pinnedAudio = preferences.getStringSet("pinned_audio", emptySet()).orEmpty().toSet()
    private val pendingClear = preferences.getStringSet("pending_clear", emptySet()).orEmpty().toMutableSet()
    @Volatile var activeKey: String? = null
        set(value) {
            field = value
            if (ready) worker.launch { clearReleasedAudio(); trim() }
        }
    @Volatile var limitGb: Int = preferences.getInt("limit_gb", 5)
        private set
    @Volatile private var ready = false
    private val evictor = object : CacheEvictor {
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = Unit
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            mutableAudioRevision.update { it + 1 }
            if (ready) worker.launch { trim() }
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { mutableAudioRevision.update { it + 1 } }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }
    val audio = SimpleCache(File(root, "audio"), evictor, StandaloneDatabaseProvider(context))
    init { ready = true; worker.launch { clearReleasedAudio(); trim() } }

    fun factory(upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory().setCache(audio)
        val streaming = CacheDataSource.Factory().setCache(audio).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { CachedPlaybackDataSource(cached, streaming, ::audioChanged) }
    }

    fun audioChanged() { mutableAudioRevision.update { it + 1 } }

    fun setLimit(gb: Int): Job {
        require(gb in listOf(1, 2, 5, 10, 20, 0))
        limitGb = gb
        preferences.edit().putInt("limit_gb", gb).apply()
        return worker.launch { trim() }
    }

    @Synchronized fun setFavorites(namespace: String, ids: Set<String>) {
        protectedTracks = protectedTracks.filterNot { it.startsWith("$namespace|") }.toSet() + ids.map { "$namespace|$it" }
        preferences.edit().putStringSet("protected", protectedTracks).apply()
        worker.launch { trim() }
    }

    @Synchronized fun changeFavorites(namespace: String, ids: List<String>, liked: Boolean) {
        val keys = ids.map { "$namespace|$it" }.toSet()
        protectedTracks = if (liked) protectedTracks + keys else protectedTracks - keys
        preferences.edit().putStringSet("protected", protectedTracks).apply()
        worker.launch { trim() }
    }

    private fun protected(key: String) = key in pinnedAudio || isProtectedAudioKey(key, protectedTracks)

    @Synchronized fun pinAudio(key: String) {
        pinnedAudio = pinnedAudio + key
        preferences.edit().putStringSet("pinned_audio", pinnedAudio).apply()
    }

    @Synchronized fun removeAudio(key: String) {
        pinnedAudio = pinnedAudio - key
        if (key == activeKey) pendingClear.add(key) else audio.removeResource(key)
        preferences.edit().putStringSet("pinned_audio", pinnedAudio)
            .putStringSet("pending_clear", pendingClear.toSet()).apply()
    }
    private fun spans() = audio.keys.flatMap { audio.getCachedSpans(it) }
    fun usage(): CacheUsage {
        val spans = spans()
        return CacheUsage(spans.filterNot { protected(it.key) }.sumOf { it.length } + files().sumOf { it.length() },
            spans.filter { protected(it.key) }.sumOf { it.length })
    }

    fun clearOrdinary() {
        spans().forEach(::removeOrdinarySpan)
        synchronized(this) { files().forEach { it.delete() } }
        ArtworkRepository.clearMemory()
    }

    fun clearAll() {
        synchronized(this) {
            audio.keys.forEach { key ->
                when (audioCacheClearAction(protected(key), key == activeKey, all = true)) {
                    AudioCacheClearAction.AFTER_PLAYBACK -> pendingClear += key
                    AudioCacheClearAction.REMOVE -> audio.removeResource(key)
                    AudioCacheClearAction.KEEP -> Unit
                }
            }
            preferences.edit().putStringSet("pending_clear", pendingClear.toSet()).apply()
            files().forEach { check(it.delete() || !it.exists()) { "缓存文件暂时无法删除" } }
        }
        ArtworkRepository.clearMemory()
    }

    private fun clearReleasedAudio() = synchronized(this) {
        pendingClear.filter { it != activeKey }.forEach { key ->
            audio.removeResource(key)
            pendingClear.remove(key)
        }
        preferences.edit().putStringSet("pending_clear", pendingClear.toSet()).apply()
    }

    private fun removeOrdinarySpan(span: CacheSpan) = synchronized(this) {
        // 收藏确认可能和后台淘汰同时发生，真正删除前再次检查最新保护状态。
        if (audioCacheClearAction(protected(span.key), span.key == activeKey, all = false) == AudioCacheClearAction.REMOVE)
            audio.removeSpan(span)
    }

    private fun files() = auxiliary.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
    private fun file(key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(auxiliary, name)
    }

    @Synchronized fun read(key: String): ByteArray? = runCatching {
        file(key).takeIf { it.isFile }?.let { it.setLastModified(System.currentTimeMillis()); it.readBytes() }
    }.getOrNull()

    @Synchronized fun write(key: String, bytes: ByteArray) {
        runCatching {
            val target = file(key)
            val temporary = File(auxiliary, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
        }
        worker.launch { trim() }
    }

    private suspend fun trim() {
        trimLock.lock()
        try {
            if (limitGb == 0) return
            val candidates = spans()
            val diskFiles = files()
            val remove = mutableMapOf<String, () -> Unit>()
            val entries = candidates.map { span ->
                val id = "audio:${span.key}:${span.position}"
                remove[id] = { removeOrdinarySpan(span) }
                CacheCandidate(id, span.length, span.lastTouchTimestamp, protected(span.key), span.key == activeKey)
            } + diskFiles.map { file ->
                val id = "file:${file.name}"
                remove[id] = { file.delete(); Unit }
                CacheCandidate(id, file.length(), file.lastModified())
            }
            cacheEvictions(entries, limitGb * 1_073_741_824L).forEach { id -> runCatching { remove[id]?.invoke() } }
        } finally { trimLock.unlock() }
    }

    companion object {
        @Volatile private var instance: MusicDiskCache? = null
        fun get(context: Context): MusicDiskCache = instance ?: synchronized(this) {
            instance ?: MusicDiskCache(context.applicationContext).also { instance = it }
        }
        fun available(): MusicDiskCache? = instance
    }
}

internal fun PlatformSession.cacheNamespace(): String = "${source.name}:${account?.userId ?: "guest"}"

internal fun playbackCacheKey(context: Context, track: MusicTrack): String {
    offlinePlaybackKey(track.previewUrl)?.let { return it }
    val session = PlatformPreferences(context).readSession(track.source)
    // CDN 票据会过期，媒体路径区分音质及试听文件；仍由原播放流程先校验权限。
    val media = android.net.Uri.parse(track.previewUrl).path.orEmpty()
    return "${session.cacheNamespace()}|${track.id}|$media"
}
