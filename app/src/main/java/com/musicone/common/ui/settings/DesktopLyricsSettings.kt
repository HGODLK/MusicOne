package com.musicone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** 桌面歌词仅 Android 12 及以上提供；子项逐行展开，分隔线由所在卡片统一绘制。 */
@Composable
internal fun DesktopLyricsSettings() {
    if (Build.VERSION.SDK_INT < 31) return
    val context = LocalContext.current
    remember(context) { DesktopLyricsOptions.initialize(context); true }
    val config by DesktopLyricsOptions.config.collectAsState()
    val revision by DesktopLyricsOptions.accessRevision.collectAsState()
    val granted = remember(context, revision) { Settings.canDrawOverlays(context) }
    var pending by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // 从系统授权页返回后立即复检；授权成功直接开启，不用再点一次。
        if (pending && Settings.canDrawOverlays(context)) DesktopLyricsOptions.update { it.copy(enabled = true) }
        pending = false
        DesktopLyricsOptions.refreshAccess()
    }
    val active = config.enabled && granted
    SettingsSwitchRow("桌面歌词",
        if (config.enabled && !granted) "悬浮窗权限已关闭，点按重新授权" else "悬浮显示歌词，进入播放页时自动隐藏", active) { value ->
        when {
            !value -> DesktopLyricsOptions.update { it.copy(enabled = false) }
            Settings.canDrawOverlays(context) -> DesktopLyricsOptions.update { it.copy(enabled = true) }
            else -> { pending = openOverlayPermission(context) }
        }
    }
    SettingsReveal(active) {
        SettingsSwitchRow("锁定位置", "锁定后歌词变淡并穿透点击；未锁定时可拖动、拖拽两侧调宽、双击暂停", config.locked) { value ->
            DesktopLyricsOptions.update { it.copy(locked = value) }
        }
    }
    SettingsReveal(active) {
        SettingsEntryRow("重置位置和宽度", "歌词拖丢或位置异常时点此恢复", trailing = {}) {
            DesktopLyricsOptions.update { it.withDefaultPlacement() }
        }
    }
    SettingsReveal(active) {
        SettingsSwitchRow("显示翻译", null, config.translation) { value ->
            DesktopLyricsOptions.update { it.copy(translation = value) }
        }
    }
    SettingsReveal(active) {
        SettingsSwitchRow("显示注音", null, config.ruby) { value -> DesktopLyricsOptions.update { it.copy(ruby = value) } }
    }
    SettingsReveal(active) {
        SettingsMenuRow("桌面歌词字体", config.font.label, DesktopLyricsFont.entries.map { font ->
            PlaybackSettingsMenuOption(font.label, font == config.font) { DesktopLyricsOptions.update { it.copy(font = font) } }
        })
    }
    SettingsReveal(active) {
        SettingsMenuRow("桌面歌词大小", config.size.label, DesktopLyricsSize.entries.map { size ->
            PlaybackSettingsMenuOption(size.label, size == config.size) { DesktopLyricsOptions.update { it.copy(size = size) } }
        })
    }
    SettingsReveal(active) {
        SettingsMenuRow("桌面歌词对齐", config.align.label, DesktopLyricsAlign.entries.map { align ->
            PlaybackSettingsMenuOption(align.label, align == config.align) { DesktopLyricsOptions.update { it.copy(align = align) } }
        })
    }
}

private fun openOverlayPermission(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false }
