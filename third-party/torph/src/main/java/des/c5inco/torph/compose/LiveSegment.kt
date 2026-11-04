package des.c5inco.torph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import des.c5inco.torph.core.Segment

/**
 * One drawn segment. All animation state is plain fields ([Channel]s) advanced by the state's frame
 * loop; nothing here is snapshot state, so per-frame reads and writes allocate nothing.
 */
internal class LiveSegment(
    var segment: Segment,
    var layout: TextLayoutResult,
    var cell: Rect,
    initial: Offset,
    initialAlpha: Float = 1f,
    initialScale: Float = 1f,
) {
    /** Stable across a persist: the diff hands the new segment the old id. */
    val id: Long get() = segment.id
    val x = Channel(initial.x)
    val y = Channel(initial.y)
    val alpha = Channel(initialAlpha)
    val scale = Channel(initialScale)
    var exiting = false
    var entering = false
    /** Clip rect for a digit sliding in or out of its cell, null otherwise. */
    var clip: Rect? = null
    /** Layer bounds and fade mask for [clip] or [strip], kept while they hold still. */
    var softClip: SoftClip? = null
    /** Odometer strip (old digit ... new digit) while a digit rolls; drawn instead of [layout]. */
    var strip: List<TextLayoutResult>? = null
    /** Index into [strip] (fractional) of the digit currently at the target position. */
    val stripProgress = Channel(0f)
    var stripRoll: Int = 0
    val width: Float get() = layout.size.width.toFloat()
    val height: Float get() = layout.size.height.toFloat()

    fun tick(now: Long): Boolean {
        var active = x.tick(now)
        active = y.tick(now) || active
        active = alpha.tick(now) || active
        active = scale.tick(now) || active
        if (stripProgress.tick(now)) {
            active = true
        } else if (strip != null) {
            strip = null
            entering = false
            clip = null
        }
        return active
    }
}
