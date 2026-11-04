package des.c5inco.torph.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import des.c5inco.torph.core.Segmentation
import des.c5inco.torph.core.formatNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Text that morphs between values: shared characters glide to their new position, new ones fade
 * in, removed ones fade out, and digits roll by place value. A port of torph for Jetpack Compose.
 *
 * Everything is drawn on one node; per-frame work is one `drawText` per live segment.
 *
 * @param text Target string. Any change starts (or retargets) a morph.
 * @param style Text style. Its colour is used unless [color] is specified.
 * @param ease Timing curve or spring.
 * @param duration Duration for [MorphEase.Curve]; ignored for springs.
 * @param scale Scale entering/exiting segments between 0.5× and 1×.
 * @param numbers Match digits by place value and roll them vertically.
 * @param locale Drives grapheme/word boundaries and number symbols.
 * @param cursorIndex Caret position when the text is being edited; the number under the caret is
 *   matched by position instead of place so typing doesn't shift every digit.
 * @param segmentation Grapheme, word, or per-word auto detection of shaping-sensitive scripts.
 * @param sizeMode Animate or snap the composable's own bounds.
 * @param clip How far past the line box rolling digits may draw; see [MorphClip].
 * @param disabled Snap every change; callbacks still fire.
 * @param respectReducedMotion Snap when the system animator scale is 0.
 * @param debug Draw segment rects, ids and enter/exit colours.
 * @param onAnimationStart Fired when a diff is applied.
 * @param onAnimationComplete Fired when every segment of that morph has settled.
 * @param onAnimationCancel Fired instead of complete when a newer morph superseded this one.
 * @param state Supply your own to inspect segments or share a state across containers.
 * @param drawColor 绘制阶段读取颜色；为空时沿用样式，不参与字形测量。
 * @param drawShadow 绘制阶段读取阴影；为空时沿用样式，不参与字形测量。
 */
@Composable
public fun TextMorph(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    ease: MorphEase = MorphEase.Curve(),
    duration: Duration = 400.milliseconds,
    scale: Boolean = true,
    numbers: Boolean = true,
    locale: Locale = Locale.current,
    cursorIndex: Int? = null,
    segmentation: Segmentation = Segmentation.AUTO,
    sizeMode: MorphSizeMode = MorphSizeMode.Animate,
    clip: MorphClip = MorphClip.Default,
    disabled: Boolean = false,
    respectReducedMotion: Boolean = true,
    debug: Boolean = false,
    onAnimationStart: (() -> Unit)? = null,
    onAnimationComplete: (() -> Unit)? = null,
    onAnimationCancel: (() -> Unit)? = null,
    state: TextMorphState = rememberTextMorphState(),
    drawColor: (() -> Color)? = null,
    drawShadow: (() -> Shadow)? = null,
) {
    val reduced = rememberReducedMotion()
    val inspection = LocalInspectionMode.current
    val resolvedColor = when {
        color.isSpecified -> color
        style.color.isSpecified -> style.color
        else -> Color.Black
    }
    val resolvedStyle = if (style.color == resolvedColor) style else style.copy(color = resolvedColor)
    val options = MorphOptions(
        ease = ease,
        duration = duration,
        scale = scale,
        numbers = numbers,
        locale = locale.toJavaLocale(),
        cursorIndex = cursorIndex,
        segmentation = segmentation,
        sizeMode = sizeMode,
        clip = clip,
        snap = disabled || inspection || (respectReducedMotion && reduced),
        debug = debug,
    )
    // Written in composition, read in layout/draw only; no composition-time animation reads.
    if (state.style != resolvedStyle) state.style = resolvedStyle
    if (state.options != options) state.options = options
    if (state.text != text) state.text = text
    SideEffect {
        state.drawColor = drawColor
        state.drawShadow = drawShadow
        state.onAnimationStart = onAnimationStart
        state.onAnimationComplete = onAnimationComplete
        state.onAnimationCancel = onAnimationCancel
    }
    Spacer(
        modifier
            .semantics { contentDescription = text }
            .textMorph(state),
    )
}

/**
 * Numeric overload: formats [value] in [locale] (grouping on) and morphs the result, with exactly
 * [decimals] fraction digits, or up to 3 when it is null, as torph does. Equivalent to
 * `TextMorph(formatNumber(value, decimals, locale))`.
 */
@Composable
public fun TextMorph(
    value: Number,
    modifier: Modifier = Modifier,
    decimals: Int? = null,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    ease: MorphEase = MorphEase.Curve(),
    duration: Duration = 400.milliseconds,
    scale: Boolean = true,
    locale: Locale = Locale.current,
    segmentation: Segmentation = Segmentation.AUTO,
    sizeMode: MorphSizeMode = MorphSizeMode.Animate,
    clip: MorphClip = MorphClip.Default,
    disabled: Boolean = false,
    respectReducedMotion: Boolean = true,
    debug: Boolean = false,
    onAnimationStart: (() -> Unit)? = null,
    onAnimationComplete: (() -> Unit)? = null,
    onAnimationCancel: (() -> Unit)? = null,
    state: TextMorphState = rememberTextMorphState(),
) {
    TextMorph(
        text = formatNumber(value, decimals, locale.toJavaLocale()),
        modifier = modifier,
        style = style,
        color = color,
        ease = ease,
        duration = duration,
        scale = scale,
        numbers = true,
        locale = locale,
        segmentation = segmentation,
        sizeMode = sizeMode,
        clip = clip,
        disabled = disabled,
        respectReducedMotion = respectReducedMotion,
        debug = debug,
        onAnimationStart = onAnimationStart,
        onAnimationComplete = onAnimationComplete,
        onAnimationCancel = onAnimationCancel,
        state = state,
    )
}

private fun Locale.toJavaLocale(): java.util.Locale = java.util.Locale.forLanguageTag(toLanguageTag())
