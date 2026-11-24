package com.musicone

import android.content.Context
import android.content.SharedPreferences
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

private const val DESKTOP_ACCENT_MIN_LUMINANCE = .36

/** 当前主音源声明的页面色，与应用主题同一来源；切换主音源、音源包就绪或增删后自动更新。 */
internal fun desktopLyricsAccents(context: Context): Flow<Int> {
    val platforms = PlatformPreferences(context)
    val controller = SourcePackageController.get(context)
    val selections = callbackFlow {
        val store = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        store.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
    return combine(selections, controller.startup, controller.installed) { _, _, _ ->
        val source = platforms.selectedSource()
        desktopLyricsAccent(if (source == MusicSource.LOCAL) source.accent
            else controller.running(source.id)?.entry?.descriptor?.presentation?.pageColorArgb)
    }.distinctUntilChanged()
}

/** 歌词没有底板：先按应用深色主题掺 25% 白，仍偏暗再逐步提亮；未声明颜色时用白色。 */
internal fun desktopLyricsAccent(pageColorArgb: Long?): Int {
    val base = validTrackThemeArgb(pageColorArgb)?.toInt() ?: return -1
    var mix = .25f
    var color = mixWhite(base, mix)
    while (HyperOsCardContrast.luminance(color) < DESKTOP_ACCENT_MIN_LUMINANCE && mix < .8f) {
        mix += .05f
        color = mixWhite(base, mix)
    }
    return color
}

private fun mixWhite(color: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val value = color ushr shift and 0xFF
        return (value + (255 - value) * fraction).toInt().coerceIn(0, 255) shl shift
    }
    return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
}
