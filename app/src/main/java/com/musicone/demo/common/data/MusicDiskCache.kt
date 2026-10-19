package com.musicone.demo

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class CacheUsage(val ordinary: Long = 0, val protected: Long = 0, val primary: Long = 0) {
    val total get() = ordinary + protected
}

/** 一套音频文件共用容量与歌单引用；所有写入入口在落盘前执行同一份准入策略。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicDiskCache private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("music_cache", Context.MODE_PRIVATE)
    private val index = MusicCacheIndex(context.getSharedPreferences("music_cache_resources", Context.MODE_PRIVATE))
    private val root = File(context.filesDir, "managed_cache").apply { mkdirs() }
    private val auxiliary = File(root, "images_and_feed").apply { mkdirs() }
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revisions = MutableStateFlow(0L)
    val audioRevision = revisions.asStateFlow()
    private val mutableLimits = MutableStateFlow(CacheLimits(
        runCatching { CacheCapacity.valueOf(preferences.getString("total_capacity", "").orEmpty()) }
            .getOrElse { CacheCapacity.fromLegacy(preferences.getInt("limit_gb", 5)) },
        runCatching { CacheCapacity.valueOf(preferences.getString("independent_capacity", "OFF").orEmpty()) }.getOrDefault(CacheCapacity.OFF)))
    val limits = mutableLimits.asStateFlow()
    private val pendingClear = preferences.getStringSet("pending_clear", emptySet()).orEmpty().toMutableSet()
    private val readers = mutableMapOf<String, Int>()
    private val writers = mutableMapOf<String, Int>()
    private val reserved = ConcurrentHashMap<String, AtomicLong>()
    private val audioBytes = ConcurrentHashMap<String, AtomicLong>()
    private var primaryAudioKeys = emptySet<String>()
    private var auxiliaryBytes = files().sumOf { it.length() }
    private var shrinkRequested = false
    @Volatile var activeKey: String? = null
        set(value) { field = value; worker.launch { clearReleasedAudio() } }
    private val evictor = object : CacheEvictor {
        override fun requiresCacheSpanTouches() = false
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = Unit
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            // 回调持有 Media3 的锁，不能反向获取本类锁；已提交字节从预留量转入真实占用。
            audioBytes.getOrPut(span.key) { AtomicLong() }.addAndGet(span.length)
            reserved[span.key]?.updateAndGet { (it - span.length).coerceAtLeast(0) }
            audioChanged()
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            audioBytes[span.key]?.addAndGet(-span.length)
            audioChanged()
        }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }
    val audio = SimpleCache(File(root, "audio"), evictor, StandaloneDatabaseProvider(context))

    init {
        importLegacyIndex()
        refreshPrimaryAudioKeys()
        worker.launch {
            setLibraryPrimary(MusicLibraryStore.get(context).primaryCacheMembers())
            clearReleasedAudio()
            removeDuplicateResources()
        }
    }

    fun audioChanged() { revisions.update { it + 1 } }

    fun factory(upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory().setCache(audio)
        return DataSource.Factory { CachedPlaybackDataSource(cached, streamingFactory(upstream), ::audioChanged,
            direct = upstream, onOpenKey = ::retainReader, onCloseKey = ::releaseReader) }
    }

    fun streamingFactory(upstream: DataSource.Factory, strict: Boolean = false): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(audio).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory { QuotaCacheDataSink(this, strict) }
            .setFlags(if (strict) CacheDataSource.FLAG_BLOCK_ON_CACHE else CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Synchronized fun register(key: String, track: MusicTrack): String {
        val physical = index.register(key, track) { audio.getCachedSpans(it).isNotEmpty() || (writers[it] ?: 0) > 0 }
        if (index.isPrimary(track.cacheIdentity())) primaryAudioKeys = primaryAudioKeys + physical
        return physical
    }

    @Synchronized fun trust(key: String, full: Boolean) { index.trust(key, full) }

    @Synchronized fun applyLimits(next: CacheLimits): Job {
        val old = mutableLimits.value
        if (old == next) return worker.launch { }
        shrinkRequested = shouldShrinkCache(old, next)
        mutableLimits.value = next
        preferences.edit().putString("total_capacity", next.total.name).putString("independent_capacity", next.independent.name).apply()
        audioChanged()
        return worker.launch {
            // 关闭只改变新增资格，不借后续读取回调自动清空旧文件。
            synchronized(this@MusicDiskCache) {
                if (mutableLimits.value != next) return@synchronized
                shrinkAfterRelease()
                audioChanged()
            }
        }
    }

    @Synchronized fun setFavorites(namespace: String, ids: Set<String>) {
        val source = MusicSource.valueOf(namespace.substringBefore(':'))
        index.replacePrimary(namespace, ids.map { cacheTrackIdentity(source, it) }, ordered = false)
        refreshPrimaryAudioKeys()
        audioChanged()
    }

    @Synchronized fun setOrderedFavorites(namespace: String, tracks: List<MusicTrack>) {
        index.replacePrimary(namespace, tracks.map { it.cacheIdentity() }, ordered = true)
        refreshPrimaryAudioKeys()
        audioChanged()
    }

    @Synchronized fun changeFavorites(namespace: String, ids: List<String>, liked: Boolean) {
        val source = MusicSource.valueOf(namespace.substringBefore(':'))
        index.changePrimary(namespace, ids.map { cacheTrackIdentity(source, it) }, liked)
        refreshPrimaryAudioKeys()
        audioChanged()
    }

    @Synchronized fun setLibraryPrimary(members: List<Pair<String, Long>>) {
        index.replaceLibraryPrimary(members)
        refreshPrimaryAudioKeys()
        audioChanged()
    }

    @Synchronized fun isPrimary(track: MusicTrack) = index.isPrimary(track.cacheIdentity())
    @Synchronized fun protectedIdentities(): Set<String> {
        val entries = resources()
        return protectedCacheKeys(entries, limits.value).mapNotNullTo(hashSetOf()) { index.resource(it)?.identity }
    }

    @Synchronized fun blockReason(track: MusicTrack): String? {
        if (track.source == MusicSource.LOCAL) return "本地音乐直接使用原文件，无需缓存"
        val entries = resources()
        val existing = entries.firstOrNull { index.resource(it.key)?.identity == track.cacheIdentity() }
        val request = CacheResource(existing?.key ?: "request:${track.cacheIdentity()}", (existing?.bytes ?: 0) + 1,
            existing?.entered ?: Long.MAX_VALUE, index.primaryOrder(track.cacheIdentity()), active = true)
        return cacheWritePlan(entries, request, limits.value).reason
    }

    @Synchronized fun blockReasons(tracks: List<MusicTrack>, usage: CacheUsage): Map<String, String> = tracks.mapNotNull { track ->
        val primary = index.isPrimary(track.cacheIdentity())
        val reason = when {
            track.source == MusicSource.LOCAL -> "本地音乐直接使用原文件，无需缓存"
            primary && limits.value.maximum == 0L -> "新增缓存已关闭"
            !primary && limits.value.total == CacheCapacity.OFF -> "总缓存已关闭"
            !primary && usage.primary >= limits.value.total.bytes -> "一级歌曲已占满总缓存额度"
            else -> null
        }
        reason?.let { track.cacheIdentity() to it }
    }.toMap()

    @Synchronized fun beginWrite(key: String, length: Long): String? {
        if (key.startsWith("uncached-trial:")) return "试听音源不写入持久缓存"
        val resource = index.resource(key) ?: return "未登记的音频不写入持久缓存"
        if (key in pendingClear) return "缓存正在等待删除"
        val primary = index.primaryOrder(resource.identity)
        val ceiling = if (primary != null) limits.value.maximum else limits.value.total.bytes
        if (ceiling == 0L) return if (primary != null) "新增缓存已关闭" else "总缓存已关闭"
        if (length > ceiling) return "单首歌曲超过缓存容量上限"
        val others = index.resources(resource.identity).filter { it.key != key &&
            (audio.getCachedSpans(it.key).isNotEmpty() || (writers[it.key] ?: 0) > 0) }
        if (others.any { inUse(it.key) }) return "同曲缓存正在使用，释放后可替换音质"
        val othersKeys = others.mapTo(hashSetOf()) { it.key }
        val entries = resources().filterNot { it.key in othersKeys }
        val existing = entries.firstOrNull { it.key == key }
        val plan = cacheWritePlan(entries, CacheResource(key, maxOf(existing?.bytes ?: 0, length.coerceAtLeast(1)),
            existing?.entered ?: Long.MAX_VALUE, primary, active = true), limits.value)
        if (!plan.allowed) return plan.reason
        // 同曲替换不保留两份持久音频；权限与音质取源已由正常解析流程核验。
        others.forEach { deleteResource(it.key) }
        if (audio.getCachedSpans(key).isEmpty() && (writers[key] ?: 0) == 0) index.forget(key)
        if (primary != null) primaryAudioKeys = primaryAudioKeys + key
        writers[key] = (writers[key] ?: 0) + 1
        reserved.getOrPut(key) { AtomicLong() }
        return null
    }

    @Synchronized fun reserve(key: String, bytes: Long): String? {
        if (key in pendingClear) return "缓存已安排删除"
        val resource = index.resource(key) ?: return "缓存写入已取消"
        val primary = index.primaryOrder(resource.identity) != null
        val ceiling = if (primary) limits.value.maximum else limits.value.total.bytes
        if (ceiling == 0L) return if (primary) "新增缓存已关闭" else "总缓存已关闭"
        // Media3 提交回调与快照使用同一把缓存锁，预留转落盘时不能短暂少计字节。
        val fits = synchronized(audio) {
            val used = audio.cacheSpace + reserved.values.sumOf { it.get() } + auxiliaryBytes
            val primaryBytes = primaryAudioKeys.sumOf { (audioBytes[it]?.get() ?: 0) + (reserved[it]?.get() ?: 0) }
            (primary || primaryBytes < ceiling) && used <= ceiling - bytes
        }
        if (fits) {
            index.markWritten(key)
            reserved.getValue(key).addAndGet(bytes)
            return null
        }
        val entries = resources()
        val current = entries.firstOrNull { it.key == key } ?: return "缓存写入已取消"
        val plan = cacheWritePlan(entries, current.copy(bytes = current.bytes + bytes, active = true), limits.value)
        if (!plan.allowed) return plan.reason
        plan.remove.forEach(::deleteResource)
        index.markWritten(key)
        reserved.getValue(key).addAndGet(bytes)
        return null
    }

    @Synchronized fun finishWrite(key: String) {
        val count = (writers[key] ?: 1) - 1
        if (count <= 0) { writers.remove(key); reserved.remove(key) } else writers[key] = count
        clearReleasedAudio()
        audioChanged()
    }


    @Synchronized private fun retainReader(key: String) { readers[key] = (readers[key] ?: 0) + 1 }
    @Synchronized private fun releaseReader(key: String) {
        val count = (readers[key] ?: 1) - 1
        if (count <= 0) readers.remove(key) else readers[key] = count
        clearReleasedAudio()
    }
    private fun inUse(key: String) = key == activeKey || (readers[key] ?: 0) > 0 || (writers[key] ?: 0) > 0

    @Synchronized fun removeAudio(key: String) {
        if (inUse(key)) pendingClear += key else deleteResource(key)
        savePendingClear()
        audioChanged()
    }

    @Synchronized fun usage(): CacheUsage {
        val entries = resources()
        val protected = protectedCacheKeys(entries, limits.value)
        return CacheUsage(entries.filterNot { it.key in protected }.sumOf { it.bytes },
            entries.filter { it.key in protected }.sumOf { it.bytes }, entries.filter { it.primaryOrder != null }.sumOf { it.bytes })
    }

    @Synchronized fun clearOrdinary() {
        val entries = resources()
        val protected = protectedCacheKeys(entries, limits.value)
        entries.filter { audioCacheClearAction(it.key in protected, it.active, false) == AudioCacheClearAction.REMOVE }
            .forEach { deleteResource(it.key) }
        ArtworkRepository.clearMemory()
        audioChanged()
    }

    @Synchronized fun clearAll() {
        resources().forEach {
            when (audioCacheClearAction(false, it.active, true)) {
                AudioCacheClearAction.AFTER_PLAYBACK -> pendingClear += it.key
                AudioCacheClearAction.REMOVE -> deleteResource(it.key)
                AudioCacheClearAction.KEEP -> Unit
            }
        }
        savePendingClear()
        ArtworkRepository.clearMemory()
        audioChanged()
    }

    @Synchronized private fun clearReleasedAudio() {
        pendingClear.filterNot(::inUse).forEach { key ->
            runCatching { deleteResource(key) }.onSuccess { pendingClear.remove(key) }
        }
        savePendingClear()
        shrinkAfterRelease()
    }

    private fun shrinkAfterRelease() {
        if (!shrinkRequested) return
        runCatching {
            cacheShrinkPlan(resources(), limits.value).forEach(::deleteResource)
            val remaining = resources()
            val primaryBytes = remaining.filter { it.primaryOrder != null }.sumOf { it.bytes }
            val bytes = remaining.sumOf { it.bytes }
            shrinkRequested = bytes > limits.value.maximum || bytes > maxOf(limits.value.total.bytes, primaryBytes)
        }
    }
    private fun savePendingClear() { preferences.edit().putStringSet("pending_clear", pendingClear.toSet()).apply() }

    private fun resources(): List<CacheResource> {
        val audioEntries = synchronized(audio) {
            (audio.keys + writers.keys).map { key ->
                val entry = index.resource(key)
                CacheResource(key, (audioBytes[key]?.get() ?: 0) + (reserved[key]?.get() ?: 0),
                    entry?.entered ?: 0, entry?.identity?.let(index::primaryOrder), inUse(key))
            }.filter { it.bytes > 0 || it.key in writers }
        }
        val files = files().map { file -> CacheResource("file:${file.name}", file.length(),
            index.auxiliaryOrder("file:${file.name}", file.lastModified() * 1_000)) }
        index.flush()
        return audioEntries + files
    }

    private fun deleteResource(key: String) {
        if (key.startsWith("file:")) {
            val file = File(auxiliary, key.removePrefix("file:"))
            val size = file.length()
            check(file.delete() || !file.exists()) { "缓存文件暂时无法删除" }
            auxiliaryBytes = (auxiliaryBytes - size).coerceAtLeast(0)
        } else audio.removeResource(key)
        index.forget(key)
    }

    private fun refreshPrimaryAudioKeys() {
        primaryAudioKeys = (audio.keys + writers.keys).filterTo(hashSetOf()) { key -> index.resource(key)?.identity?.let(index::isPrimary) == true }
    }

    private fun files() = auxiliary.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
    private fun file(key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(auxiliary, name)
    }
    @Synchronized fun read(key: String): ByteArray? = runCatching { file(key).takeIf { it.isFile }?.readBytes() }.getOrNull()

    @Synchronized fun write(key: String, bytes: ByteArray) {
        val target = file(key)
        val resource = "file:${target.name}"
        val entries = resources()
        val plan = cacheWritePlan(entries, CacheResource(resource, bytes.size.toLong(),
            entries.firstOrNull { it.key == resource }?.entered ?: Long.MAX_VALUE, active = true), limits.value)
        if (!plan.allowed) return
        plan.remove.forEach(::deleteResource)
        runCatching {
            val oldSize = target.length()
            val temporary = File(auxiliary, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (temporary.renameTo(target)) auxiliaryBytes += bytes.size - oldSize else temporary.delete()
            index.auxiliaryOrder(resource)
            index.flush()
        }
        audioChanged()
    }
    @Synchronized fun delete(key: String) { deleteResource("file:${file(key).name}"); audioChanged() }

    @Synchronized private fun importLegacyIndex() {
        listOf("offline_music_index", "supplemental_audio_index").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, raw) ->
                runCatching { JSONObject(raw as String) }.getOrNull()?.let { row ->
                    row.toQqStoredTrack()?.let { track ->
                        val physical = row.optString("cacheKey", key)
                        index.importResource(physical, track, audio.getCachedSpans(physical).minOfOrNull { it.lastTouchTimestamp * 1_000 } ?: 0)
                        index.trust(physical, !row.optBoolean("pending") && !row.optBoolean("trial"))
                    }
                }
            }
        }
        audio.keys.filter { index.resource(it) == null && '|' in it }.forEach { key ->
            val source = MusicSource.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return@forEach
            index.importIdentity(key, cacheTrackIdentity(source, key.substringAfter('|').substringBefore('|')),
                audio.getCachedSpans(key).minOfOrNull { it.lastTouchTimestamp * 1_000 } ?: 0)
        }
        if (!preferences.getBoolean("primary_migrated", false)) {
            preferences.getStringSet("protected", emptySet()).orEmpty().groupBy { it.substringBefore('|') }.forEach { (namespace, ids) ->
                setFavorites(namespace, ids.mapTo(linkedSetOf()) { it.substringAfter('|') })
            }
            preferences.edit().putBoolean("primary_migrated", true).remove("pinned_audio").remove("protected").apply()
        }
    }

    @Synchronized private fun removeDuplicateResources() {
        audio.keys.groupBy { index.resource(it)?.identity ?: it }.values.filter { it.size > 1 }.forEach { keys ->
            val keep = keys.filter(::inUse).firstOrNull() ?: keys.maxByOrNull { key ->
                val length = ContentMetadata.getContentLength(audio.getContentMetadata(key))
                if (length > 0 && audio.isCached(key, 0, length)) Long.MAX_VALUE else audio.getCachedSpans(key).sumOf { it.length }
            }
            keys.filter { it != keep && !inUse(it) }.forEach(::deleteResource)
        }
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
    val raw = SupplementalPlaybackRequests.cacheKey(track.previewUrl) ?: networkPlaybackCacheKey(context, track)
    if (raw.startsWith("uncached-trial:")) return raw
    return MusicDiskCache.get(context).register(raw, track)
}

internal fun networkPlaybackCacheKey(context: Context, track: MusicTrack): String {
    val session = PlatformPreferences(context).readSession(track.source)
    return "${session.cacheNamespace()}|${track.id}|${android.net.Uri.parse(track.previewUrl).path.orEmpty()}"
}
