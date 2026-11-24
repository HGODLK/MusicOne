package com.musicone

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 仅消费通用播放快照；不持有音源、账号、解析器、媒体会话或第二份队列。 */
@RequiresApi(31)
internal class DesktopLyricsCoordinator(
    private val context: Context,
    private val presentations: Flow<SystemPlaybackPresentation?>,
    private val togglePlay: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accessListener = AppOpsManager.OnOpChangedListener { _, name ->
        if (name == null || name == context.packageName) DesktopLyricsOptions.refreshAccess()
    }

    fun start() {
        DesktopLyricsOptions.initialize(context)
        ExperiencePreferences.initialize(context)
        // 部分系统会限制监听，失败时仍由设置页返回和下次开启重新检查。
        try {
            context.getSystemService(AppOpsManager::class.java)
                .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, accessListener)
        } catch (error: RuntimeException) { Log.w(TAG, "悬浮窗权限监听不可用：${error.javaClass.simpleName}") }
        scope.launch {
            combine(DesktopLyricsOptions.config.map { it.enabled }, DesktopLyricsOptions.accessRevision) { enabled, _ ->
                enabled && Settings.canDrawOverlays(context)
            }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                try { run() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w(TAG, "桌面歌词停止：${error.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun run() = coroutineScope {
        val window = DesktopLyricsWindow(context, togglePlay)
        val hasTrack = MutableStateFlow(false)
        try {
            launch {
                var loadedMode: AppFontMode? = null
                var typefaces = DesktopLyricTypefaces.system
                combine(DesktopLyricsOptions.config, snapshotFlow { ExperiencePreferences.options },
                    desktopLyricsAccents(context)) { config, options, accent -> Triple(config, options, accent)
                }.distinctUntilChanged().collectLatest { (config, options, accent) ->
                    val mode = if (config.font == DesktopLyricsFont.SANS_SERIF) AppFontMode.SANS_SERIF else options.fontMode
                    if (mode != loadedMode) {
                        typefaces = DesktopLyricTypefaces.load(context, mode)
                        loadedMode = mode
                    }
                    window.apply(config, DesktopLyricStyle(window.density, typefaces, config.size.dp, config.align, accent,
                        config.translation, config.ruby, options.verbatimLyricAnimation, marquee = !options.reduceMotion), options.reduceMotion,
                        options.lyricAnimationFrameRate.fps.takeIf { it > 0 } ?: DesktopLyricsView.DESKTOP_MAX_FPS)
                }
            }
            launch {
                combine(hasTrack, DesktopLyricsPresence.playerVisible) { track, player -> track && !player }
                    .distinctUntilChanged().collect(window::setVisible)
            }
            presentations.catch { error ->
                Log.w(TAG, "播放快照读取失败：${error.javaClass.simpleName}")
                emit(null)
            }.collect { snapshot ->
                window.present(snapshot)
                hasTrack.value = snapshot != null
            }
        } finally { window.dismiss() }
    }

    fun close() {
        try { context.getSystemService(AppOpsManager::class.java).stopWatchingMode(accessListener) }
        catch (_: RuntimeException) { }
        scope.cancel()
    }

    private companion object { const val TAG = "MusicOneDesktopLyrics" }
}
