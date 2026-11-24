package com.musicone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DesktopLyricsFont(val storedValue: String, val label: String) {
    FOLLOW_APP("follow", "跟随应用"),
    SANS_SERIF("sans_serif", "黑体");

    companion object {
        fun fromStored(value: String?): DesktopLyricsFont = entries.firstOrNull { it.storedValue == value } ?: FOLLOW_APP
    }
}

/** 字号按 dp 取值，不随系统字体缩放。 */
internal enum class DesktopLyricsSize(val dp: Int, val label: String) {
    XS(16, "16"), S(18, "18"), M(20, "20"), L(22, "22"), XL(24, "24"), XXL(26, "26");

    companion object {
        fun fromStored(dp: Int): DesktopLyricsSize = entries.firstOrNull { it.dp == dp } ?: M
    }
}

/** 只决定放得下的行停在哪一侧；超长行始终从行首滚动。 */
internal enum class DesktopLyricsAlign(val storedValue: String, val label: String) {
    LEFT("left", "居左"),
    CENTER("center", "居中"),
    RIGHT("right", "居右");

    fun rowStart(width: Float, rowWidth: Float, inset: Float): Float = when (this) {
        LEFT -> inset
        CENTER -> (width - rowWidth) / 2f
        RIGHT -> width - inset - rowWidth
    }

    companion object {
        fun fromStored(value: String?): DesktopLyricsAlign = entries.firstOrNull { it.storedValue == value } ?: CENTER
    }
}

internal data class DesktopLyricsConfig(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val translation: Boolean = true,
    val ruby: Boolean = false,
    val font: DesktopLyricsFont = DesktopLyricsFont.FOLLOW_APP,
    val size: DesktopLyricsSize = DesktopLyricsSize.M,
    val align: DesktopLyricsAlign = DesktopLyricsAlign.CENTER,
    // 位置是可用区域内的相对比例，旋转、分屏后按新窗口换算。
    val fractionX: Float = .5f,
    val fractionY: Float = .12f,
    // 窄屏与宽屏各记一份宽度（dp），0 表示按屏幕取默认宽度。
    val narrowWidthDp: Float = 0f,
    val wideWidthDp: Float = 0f,
) {
    fun widthDp(availableDp: Float): Float = if (desktopLyricsWide(availableDp)) wideWidthDp else narrowWidthDp

    fun withWidthDp(availableDp: Float, value: Float): DesktopLyricsConfig =
        if (desktopLyricsWide(availableDp)) copy(wideWidthDp = value) else copy(narrowWidthDp = value)

    fun withDefaultPlacement(): DesktopLyricsConfig = DesktopLyricsConfig().let {
        copy(fractionX = it.fractionX, fractionY = it.fractionY, narrowWidthDp = it.narrowWidthDp, wideWidthDp = it.wideWidthDp)
    }
}

/** 设备级设置，所有音源共用，不参与账号和音乐库同步。 */
internal object DesktopLyricsOptions {
    private var store: SharedPreferences? = null
    private val mutableConfig = MutableStateFlow(DesktopLyricsConfig())
    private val mutableAccessRevision = MutableStateFlow(0L)
    val config = mutableConfig.asStateFlow()

    /** 悬浮窗权限可能在系统设置里变化，变化后由设置页或系统监听推动重新检查。 */
    val accessRevision = mutableAccessRevision.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val preferences = context.applicationContext.getSharedPreferences("desktop_lyrics", Context.MODE_PRIVATE)
        store = preferences
        mutableConfig.value = DesktopLyricsConfig(
            enabled = preferences.getBoolean("enabled", false),
            locked = preferences.getBoolean("locked", false),
            translation = preferences.getBoolean("translation", true),
            ruby = preferences.getBoolean("ruby", false),
            font = DesktopLyricsFont.fromStored(preferences.getString("font", null)),
            size = DesktopLyricsSize.fromStored(preferences.getInt("size", 0)),
            align = DesktopLyricsAlign.fromStored(preferences.getString("align", null)),
            fractionX = preferences.getFloat("fraction_x", .5f).coerceIn(0f, 1f),
            fractionY = preferences.getFloat("fraction_y", .12f).coerceIn(0f, 1f),
            narrowWidthDp = preferences.getFloat("width_narrow", 0f).coerceAtLeast(0f),
            wideWidthDp = preferences.getFloat("width_wide", 0f).coerceAtLeast(0f),
        )
    }

    fun update(change: (DesktopLyricsConfig) -> DesktopLyricsConfig) {
        val value = change(mutableConfig.value)
        if (value == mutableConfig.value) return
        mutableConfig.value = value
        store?.edit()?.putBoolean("enabled", value.enabled)
            ?.putBoolean("locked", value.locked)
            ?.putBoolean("translation", value.translation)
            ?.putBoolean("ruby", value.ruby)
            ?.putString("font", value.font.storedValue)
            ?.putInt("size", value.size.dp)
            ?.putString("align", value.align.storedValue)
            ?.putFloat("fraction_x", value.fractionX)
            ?.putFloat("fraction_y", value.fractionY)
            ?.putFloat("width_narrow", value.narrowWidthDp)
            ?.putFloat("width_wide", value.wideWidthDp)?.apply()
    }

    fun refreshAccess() { mutableAccessRevision.value += 1L }
}
