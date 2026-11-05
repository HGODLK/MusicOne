package com.musicone.sourceintegration

import android.content.Context
import android.util.Log
import com.musicone.*
import kotlinx.coroutines.*

/** 续页内容由包解释；宿主仅去重并保留原有电台队列窗口。 */
internal class SourceRadioRepository(private val context: Context, private val source: () -> MusicSource) {
    private val continuation = SourceRadioContinuation()

    fun available(source: MusicSource): Boolean =
        SourcePackageController.get(context).running(source.id)?.entry?.radio != null

    fun resetSession() = continuation.reset()

    suspend fun load(existingIds: Set<String>, targetSize: Int): List<MusicTrack> {
        if (targetSize <= 0) return emptyList()
        // 切到 IO 前固定音源和会话，迟到的旧请求不会影响新会话。
        val sourceId = source().id
        if (existingIds.isEmpty()) continuation.reset()
        val session = continuation.session()
        return withContext(Dispatchers.IO) { loadPages(sourceId, session, existingIds, targetSize) }
    }

    private suspend fun loadPages(sourceId: String, session: Long, existingIds: Set<String>, targetSize: Int): List<MusicTrack> = coroutineScope {
        SourcePackageController.get(context).awaitStartup()
        val radio = SourcePackageController.get(context).running(sourceId)?.entry?.radio
            ?: throw PlatformApiException("此音源未提供电台")
        val tracks = linkedMapOf<String, MusicTrack>()
        repeat(4) {
            ensureActive()
            val page = radio.next(continuation.read(session, sourceId))
            continuation.write(session, sourceId, page.continuation)
            page.tracks.forEach { item ->
                val track = item.toMusicTrack()
                if (track.id !in existingIds) tracks.getOrPut(track.id) { track }
            }
            if (tracks.size >= targetSize) return@coroutineScope tracks.values.take(targetSize)
        }
        tracks.values.take(targetSize)
    }
}

internal class SourceRecentPlayReporter(private val scope: CoroutineScope,
    private val write: suspend (MusicTrack, Long) -> Boolean,
    private val changed: () -> Unit = {}) {
    constructor(context: Context, scope: CoroutineScope) : this(scope, report@{ track, played ->
        SourcePackageController.get(context).awaitStartup()
        val service = SourcePackageController.get(context).running(track.source.id)?.entry?.discovery ?: return@report false
        service.reportPlayed(track.toSourceTrack(), played)
        true
    }, SourceRecentPlayChanges::publish)
    private val reported = mutableSetOf<Long>()
    fun report(track: MusicTrack, generation: Long, playedMs: Long) {
        if (playedMs <= 0L || !reported.add(generation)) return
        scope.launch {
            try { if (write(track, playedMs)) changed() else reported.remove(generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("SourceRecentPlay", "最近播放上报失败：${failure.javaClass.simpleName}") }
        }
    }
}
