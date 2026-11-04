package des.c5inco.torph.compose

import des.c5inco.torph.core.Segmentation
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Whether the composable's bounds animate to the new text's size or jump straight to it. */
public enum class MorphSizeMode { Animate, Snap }

/**
 * Where a rolling or sliding digit stops being drawn, set with `TextMorph(clip = ...)`. By default a
 * digit may show a quarter of a line past the text's own line box and fades out across that margin:
 * soft, but the neighbouring digits peek out above and below. Give an [overflow] of 0 to keep every
 * digit inside the line box, so a figure sitting tight against other text never paints over it, or a
 * negative one to clip inside it, to the band the digits themselves occupy, like the window of a
 * mechanical counter.
 *
 * @property overflow how far past the line box, as a fraction of the line height, a digit may draw.
 *   Negative values clip inside the line box.
 * @property inset how far inside the line box, as a fraction of the line height, the fade completes.
 *   Keep it larger than `-overflow`, and smaller than the space between the line box and the glyphs'
 *   ink, or settled digits will be faded at their edges.
 */
public data class MorphClip(val overflow: Float = 0.25f, val inset: Float = 0.1f) {
    public companion object {
        /**
         * A quarter-line margin past the line box, fading until a tenth of a line inside it: how this
         * library has always drawn rolling digits.
         */
        public val Default: MorphClip = MorphClip()

        /**
         * Clipped to the line box, fading in its outer [inset] only. Close to torph on the web, which
         * clips at the line box and fades over 0.15em.
         */
        public fun lineBox(inset: Float = 0.12f): MorphClip = MorphClip(overflow = 0f, inset = inset)

        /** Clipped [margin] inside the line box, fading until [inset]: a counter's window. */
        public fun window(margin: Float = 0.06f, inset: Float = 0.13f): MorphClip = MorphClip(overflow = -margin, inset = inset)
    }
}

/** Everything that tunes a morph, held on [TextMorphState.options]. */
public data class MorphOptions(
    val ease: MorphEase = MorphEase.Curve(),
    val duration: Duration = 400.milliseconds,
    /** Scale entering/exiting segments between 0.5 and 1. */
    val scale: Boolean = true,
    /** Place-value matching and vertical rolling for numbers. */
    val numbers: Boolean = true,
    val locale: Locale = Locale.getDefault(),
    /** Caret position for editable text: the number under the caret is matched by position, not place. */
    val cursorIndex: Int? = null,
    val segmentation: Segmentation = Segmentation.AUTO,
    val sizeMode: MorphSizeMode = MorphSizeMode.Animate,
    /** Snap every change; set by `disabled`, reduced motion, or inspection mode. */
    val snap: Boolean = false,
    /** Where rolling and sliding digits are clipped; see [MorphClip]. */
    val clip: MorphClip = MorphClip.Default,
    /** Draw segment rects, ids and enter/exit colouring. */
    val debug: Boolean = false,
    /** Above this many segments, fall back to word segmentation. */
    val maxSegments: Int = 300,
)
