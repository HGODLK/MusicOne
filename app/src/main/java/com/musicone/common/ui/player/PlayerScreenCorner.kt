package com.musicone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/** 四个角取最小值，偏小会被屏幕自身遮住、偏大会露出下层；任意一角缺失（分屏、小窗、直角屏）即视为没有圆角。 */
internal fun reportedScreenCornerPx(radii: List<Int?>): Int =
    if (radii.isEmpty() || radii.any { it == null }) 0 else radii.minOf { it ?: 0 }.coerceAtLeast(0)

/**
 * 标准接口报 0、应用又读不到厂商私有属性的机型，按实测值登记；只收实测过的型号，其余保持直角。
 * 华为 P50E（ABR-AL80）：物理短边 1224px，上角 126.22px、下角 135.23px，取较小者并随渲染分辨率缩放。
 */
internal fun knownScreenCornerPx(manufacturer: String, model: String, shortSidePx: Int): Float =
    if (shortSidePx > 0 && manufacturer.equals("HUAWEI", ignoreCase = true) &&
        model.replace('_', '-').equals("ABR-AL80", ignoreCase = true)) 126.22f * shortSidePx / 1224f else 0f

/** 安卓 11 及以下没有标准接口，读系统内置圆角尺寸：上、下各自未设置时用通用值。 */
internal fun legacyScreenCornerPx(top: Int, bottom: Int, general: Int): Int =
    minOf(if (top > 0) top else general, if (bottom > 0) bottom else general).coerceAtLeast(0)

@RequiresApi(Build.VERSION_CODES.S)
private fun windowScreenCornerPx(insets: WindowInsets): Int = reportedScreenCornerPx(listOf(
    RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
).map { insets.getRoundedCorner(it)?.radius })

private fun systemDimenPx(name: String): Int {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier(name, "dimen", "android")
    return if (id == 0) 0 else runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0)
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

@Suppress("DEPRECATION")
private fun screenCornerPx(view: View): Float {
    // 必须从窗口取：窗口不贴屏幕角时接口返回空，显示器对象上的值不随窗口位置变化。
    val insets = view.rootWindowInsets ?: return 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val reported = windowScreenCornerPx(insets)
        if (reported > 0) return reported.toFloat()
    }
    // 以下两种来源不知道窗口位置，只在窗口铺满屏幕时使用。
    val display = view.display ?: return 0f
    val metrics = DisplayMetrics()
    display.getRealMetrics(metrics)
    val root = view.rootView
    // Android 7 以下没有分屏，也没有这个接口。
    val multiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && view.context.hostActivity()?.isInMultiWindowMode == true
    if (multiWindow ||
        root.width < metrics.widthPixels || root.height < metrics.heightPixels) return 0f
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val legacy = legacyScreenCornerPx(systemDimenPx("rounded_corner_radius_top"),
            systemDimenPx("rounded_corner_radius_bottom"), systemDimenPx("rounded_corner_radius"))
        if (legacy > 0) return legacy.toFloat()
    }
    return knownScreenCornerPx(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(),
        minOf(metrics.widthPixels, metrics.heightPixels))
}

/** 把当前窗口的屏幕圆角同步为播放页展开的终点圆角；旋转、分屏、折叠屏换屏等布局变化后重新取值。 */
@Composable
internal fun PlayerScreenCornerEffect() {
    val view = LocalView.current
    val density = LocalDensity.current.density
    DisposableEffect(view, density) {
        val update = { MiniPlayerSurfaceCorner.screenDp = screenCornerPx(view) / density }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        view.addOnLayoutChangeListener(listener)
        update()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
}
