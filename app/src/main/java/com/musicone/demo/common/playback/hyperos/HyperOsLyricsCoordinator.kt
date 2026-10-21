package com.musicone.demo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 仅消费通用展示快照；不持有音源、账号、解析器、媒体会话或第二份队列。 */
@RequiresApi(26)
internal class HyperOsLyricsCoordinator(
    private val context: Context,
    private val presentations: Flow<SystemPlaybackPresentation?>,
    notifications: HyperOsMediaNotificationProvider,
    private val loadArtwork: suspend (String) -> Bitmap?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val publisher = HyperOsIslandPublisher(context, notifications)
    private val windows = HyperOsLyricWindows(context.resources)
    private val accessRevision = MutableStateFlow(0L)
    private var started = false

    fun start() {
        if (started || !HyperOsIslandAccess.supported(context)) return
        started = true
        HyperOsLyricsOptions.initialize(context)
        scope.launch {
            combine(HyperOsLyricsOptions.enabled, HyperOsLyricsOptions.standardTemplate, accessRevision) { enabled, standard, _ ->
                Triple(enabled, standard, HyperOsIslandAccess.notificationsAllowed(context))
            }
                .distinctUntilChanged()
                .collectLatest { (enabled, standard, permission) ->
                    publisher.clear()
                    when {
                        !enabled -> HyperOsIslandDiagnostics.waiting("未启用")
                        !permission -> HyperOsIslandDiagnostics.waiting("通知权限未开启")
                        else -> {
                            HyperOsIslandDiagnostics.waiting("等待播放")
                            try {
                                ensureHyperOsIslandChannel(context)
                                observePlayback(standard)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                HyperOsIslandDiagnostics.failed("读取播放状态失败（${error.javaClass.simpleName}）")
                            }
                        }
                    }
                }
        }
    }

    fun refreshAccess() { accessRevision.value += 1L }

    private suspend fun observePlayback(standard: Boolean) = coroutineScope {
        val current = MutableStateFlow<SystemPlaybackPresentation?>(null)
        val artwork = MutableStateFlow<IslandArtwork?>(null)
        var artworkKey: SystemArtworkKey? = null
        var artworkJob: Job? = null
        try {
            launch {
                presentations.catch { error ->
                    Log.w("MusicOneHyperOs", "系统歌词数据读取失败：${error.javaClass.simpleName}")
                    HyperOsIslandDiagnostics.failed("读取播放快照失败（${error.javaClass.simpleName}）")
                    emit(null)
                }.collect { snapshot ->
                    current.value = snapshot
                    val key = snapshot?.let { SystemArtworkKey(it.key, it.artworkUri) }
                    if (key != artworkKey) {
                        artworkJob?.cancel()
                        artworkKey = key
                        val uri = key?.uri?.takeIf(String::isNotBlank)
                        artwork.value = key?.let { IslandArtwork(it, null) }
                        if (uri != null) artworkJob = launch {
                            // 歌词先用中性封面发布，图片异步回填不再阻断首帧。
                            val bitmap = try {
                                loadArtwork(uri)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            ensureActive()
                            // 同一首重播也有新代次；旧加载完成不能把封面交给新请求。
                            if (artworkKey == key) artwork.value = IslandArtwork(key, bitmap)
                        }
                    }
                }
            }
            combine(current, artwork) { snapshot, cover -> snapshot to cover }.collectLatest { (snapshot, cover) ->
                if (snapshot == null) {
                    publisher.clear()
                    if (!HyperOsIslandDiagnostics.state.value.message.startsWith("提交失败"))
                        HyperOsIslandDiagnostics.waiting("等待播放：尚无播放快照")
                    return@collectLatest
                }
                if (!snapshot.readyForPresentation) {
                    HyperOsIslandDiagnostics.waiting("准备中：歌曲与播放器身份尚未对齐")
                    return@collectLatest
                }
                val matching = cover?.takeIf { it.key == SystemArtworkKey(snapshot.key, snapshot.artworkUri) }
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val content = snapshot.hyperOsContentAt(now)
                    val frame = windows.frame(snapshot, now, content.lyric)
                    publisher.publish(matching?.bitmap, HyperOsIslandFrame(snapshot.key,
                        windows.summary(content, frame.text), content.title, content.artist,
                        snapshot.positionAt(now) / 1_000L, snapshot.durationMs / 1_000L), standard)
                    val segmentDelay = if (snapshot.advancing) frame.nextPositionMs?.let {
                        (it - snapshot.positionAt(now)).coerceAtLeast(16L)
                    } else null
                    delay(minOf(snapshot.nextUpdateDelayMs(now), segmentDelay ?: 1_000L))
                }
            }
        } finally {
            artworkJob?.cancel()
            publisher.clear()
        }
    }

    fun close() {
        scope.cancel()
        publisher.clear()
    }

    private data class IslandArtwork(val key: SystemArtworkKey, val bitmap: Bitmap?)
}
