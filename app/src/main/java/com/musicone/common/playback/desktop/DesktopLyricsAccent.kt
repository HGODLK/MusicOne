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
import kotlin.math.pow

private const val DESKTOP_ACCENT_MIN_LUMINANCE = .36

/** 按当前主音源的主题色生成桌面歌词高亮色；切音源或音源包就绪时自动更新。 */
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

/** 桌面歌词没有底板，默认先混入 25% 白色，还太暗就继续提亮；没配主题色时直接用白色。 */
internal fun desktopLyricsAccent(pageColorArgb: Long?): Int {
    val base = validTrackThemeArgb(pageColorArgb)?.toInt() ?: return -1
    var mix = .25f
    var color = mixWhite(base, mix)
    while (desktopColorLuminance(color) < DESKTOP_ACCENT_MIN_LUMINANCE && mix < .8f) {
        mix += .05f
        color = mixWhite(base, mix)
    }
    return color
}

/** 计算 ARGB 颜色的相对亮度（WCAG sRGB 公式），用来判断颜色是否偏暗或接近白色。 */
internal fun desktopColorLuminance(color: Int): Double {
    fun linear(shift: Int): Double {
        val value = ((color ushr shift) and 255) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}

private fun mixWhite(color: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val value = color ushr shift and 0xFF
        return (value + (255 - value) * fraction).toInt().coerceIn(0, 255) shl shift
    }
    return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
}
