package des.c5inco.torph.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import des.c5inco.torph.core.DiffResult
import des.c5inco.torph.core.Segment
import des.c5inco.torph.core.SegmentKind
import des.c5inco.torph.core.SegmentOptions
import des.c5inco.torph.core.Segmentation
import des.c5inco.torph.core.Segmenter
import des.c5inco.torph.core.diffSegments
import des.c5inco.torph.core.segmentText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Segment layouts kept across text changes: a few screens of distinct words. */
private const val MAX_CACHED_LAYOUTS = 1024

/**
 * Owns the segment list, animation channels and layout cache behind [TextMorph]. Create with
 * [rememberTextMorphState] and attach with [Modifier.textMorph] for custom containers.
 */
public class TextMorphState internal constructor(
    /** The measurer used for every layout, including the debug overlay. */
    internal val textMeasurer: TextMeasurer,
    private val scope: CoroutineScope,
    private val segmenter: Segmenter,
) {
    /** Target text. Setting it (from composition) schedules a morph in the next layout pass. */
    public var text: String by mutableStateOf("")

    /** Resolved style, including colour. Changing it re-measures without morphing. */
    public var style: TextStyle by mutableStateOf(TextStyle.Default)

    // 绘制属性独立于布局样式，颜色过渡不触发字形重新测量。
    internal var drawColor: (() -> androidx.compose.ui.graphics.Color)? by mutableStateOf(null)
    internal var drawShadow: (() -> androidx.compose.ui.graphics.Shadow)? by mutableStateOf(null)

    /** Timing, matching and rendering options. Read in the layout and draw passes. */
    public var options: MorphOptions by mutableStateOf(MorphOptions())

    /** See the matching [TextMorph] parameters. Exactly one of complete/cancel follows each start. */
    public var onAnimationStart: (() -> Unit)? = null
    public var onAnimationComplete: (() -> Unit)? = null
    public var onAnimationCancel: (() -> Unit)? = null

    /** True between `onAnimationStart` and the matching complete/cancel. */
    public var isAnimating: Boolean by mutableStateOf(false)
        private set

    /** Current segments of [text] with resolved ids; the `old` side of the next diff. */
    public var segments: List<Segment> = emptyList()
        private set

    /** Most recent full-string layout, or null before the first measure. */
    public var layoutResult: TextLayoutResult? = null
        private set

    /** Number of live (drawn) segments, including ones still exiting. */
    public val liveCount: Int get() = live.size

    internal val live = ArrayList<LiveSegment>()

    /** Bumped once per animation frame (and per structural change); the draw lambda reads it. */
    internal val frameTick = mutableIntStateOf(0)

    /** Bumped only while the container size animates; the layout modifier reads it. */
    private val sizeTick = mutableIntStateOf(0)
    private val width = Channel(0f)
    private val height = Channel(0f)
    private var lastSizeMoving = false

    private var nextId = 0L
    private var lastKey: LayoutKey? = null
    private var lastSnap = false
    private val layoutCache = HashMap<String, TextLayoutResult>()
    private var cacheStyle: TextStyle? = null
    /** Per-character (left, top, right, bottom) boxes of [charBoxesFor], filled once per layout for [place]. */
    private var charBoxes = FloatArray(0)
    private var charBoxesFor: TextLayoutResult? = null

    private val timings = MorphTimings()
    private var loop: Job? = null
    private var lastFrameNanos = Channel.UNSET
    private var generationAnimating = false

    private data class LayoutKey(
        val text: String,
        val style: TextStyle,
        val maxWidth: Int,
        val direction: LayoutDirection,
        val segmentation: Segmentation,
        val numbers: Boolean,
        val locale: Locale,
        val maxSegments: Int,
    )

    // region layout pass

    /** Called from the layout modifier. Applies pending text changes and returns the size to occupy. */
    internal fun measure(constraints: Constraints, direction: LayoutDirection): IntSize {
        val opts = options
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val key = LayoutKey(text, style, maxWidth, direction, opts.segmentation, opts.numbers, opts.locale, opts.maxSegments)
        val prev = lastKey
        val becameSnap = opts.snap && !lastSnap
        lastSnap = opts.snap
        if (becameSnap) {
            loop?.cancel()
            loop = null
            lastFrameNanos = Channel.UNSET
        }
        if (key != prev) {
            lastKey = key
            apply(key, opts, prev)
        } else if (becameSnap) {
            // 不可见或关闭动画时立即结束旧滚动，复用已有布局，后续测量不重复重建。
            layoutResult?.let { full ->
                snapToLayout(full, Size(full.size.width.toFloat(), full.size.height.toFloat()), notify = false)
            }
        }
        sizeTick.intValue // subscribe: re-measure while the size animates
        return constraints.constrain(IntSize(ceil(width.value).toInt(), ceil(height.value).toInt()))
    }

    /**
     * Re-measures [key]. When its text differs from [prev]'s (null on the first pass) the new text is
     * diffed into [segments] and morphed to, or snapped to when [MorphOptions.snap] is set. When only
     * the options that shape segmentation changed, the same text is re-segmented and snapped to.
     */
    private fun apply(key: LayoutKey, opts: MorphOptions, prev: LayoutKey?) {
        val diag = TextMorphDiagnostics.logTimings
        if (diag) timings.reset()
        val applyStart = if (diag) System.nanoTime() else 0L
        var mark = applyStart
        val full = textMeasurer.measure(
            text = AnnotatedString(key.text),
            style = key.style,
            softWrap = true,
            constraints = if (key.maxWidth == Constraints.Infinity) Constraints() else Constraints(maxWidth = key.maxWidth),
        )
        if (diag) { val n = System.nanoTime(); timings.measureText = n - mark; mark = n }
        layoutResult = full
        // Text that keeps introducing new words would otherwise grow the cache without bound. Live
        // segments hold their own layouts, so clearing only costs re-measuring the next change.
        if (cacheStyle != key.style || layoutCache.size > MAX_CACHED_LAYOUTS) { layoutCache.clear(); cacheStyle = key.style }
        val newSize = Size(full.size.width.toFloat(), full.size.height.toFloat())

        val textChanged = key.text != prev?.text
        val segmentationChanged = prev != null && (
            key.segmentation != prev.segmentation || key.numbers != prev.numbers ||
                key.locale != prev.locale || key.maxSegments != prev.maxSegments
            )
        var diff: DiffResult? = null
        if (textChanged || segmentationChanged) {
            val segOptions = SegmentOptions(opts.segmentation, opts.numbers, opts.cursorIndex, opts.maxSegments)
            val incoming = segmentText(key.text, opts.locale, segmenter, segOptions, nextId)
            if (diag) { val n = System.nanoTime(); timings.segment = n - mark; mark = n }
            diff = diffSegments(segments, incoming, opts.locale)
            if (diag) { val n = System.nanoTime(); timings.diff = n - mark; mark = n }
            segments = diff.segments
            nextId = diff.nextId
        }

        if (diff == null || !textChanged || prev == null || opts.snap) {
            // Initial layout, re-wrap or re-segment without a text change, or snapping: rebuild
            // everything in place. A snapped text change still fires its callbacks, in order.
            snapToLayout(full, newSize, notify = textChanged && prev != null && diff?.isEmpty == false)
            return
        }

        startMorph(diff, full, newSize, opts)
        if (diag) {
            val n = System.nanoTime()
            // Placement and segment measuring happen inside startMorph; the rest is animation setup.
            timings.animation = (n - mark) - timings.place - timings.segmentLayout
            timings.log(
                totalNs = n - applyStart,
                chars = key.text.length,
                segments = diff.segments.size,
                persist = diff.persist.size,
                enter = diff.enter.size,
                exit = diff.exit.size,
            )
        }
    }

    private fun snapToLayout(full: TextLayoutResult, newSize: Size, notify: Boolean) {
        finishGeneration(cancelled = true)
        live.clear()
        for (s in segments) {
            val (target, cell) = place(s, full)
            live.add(LiveSegment(s, segmentLayout(s), cell, target))
        }
        width.snapTo(newSize.width)
        height.snapTo(newSize.height)
        frameTick.intValue++
        if (notify) scope.launch { onAnimationStart?.invoke(); onAnimationComplete?.invoke() }
    }

    private fun startMorph(diff: DiffResult, full: TextLayoutResult, newSize: Size, opts: MorphOptions) {
        val now = if (loop?.isActive == true) lastFrameNanos else Channel.UNSET
        val moveSpec = opts.ease.toFloatSpec(opts.duration, 0.5f)
        val fadeSpec = opts.ease.toFloatSpec(opts.duration, 0.001f)
        val minScale = if (opts.scale) 0.5f else 1f
        val byId = HashMap<Long, LiveSegment>(live.size)
        for (ls in live) if (!ls.exiting) byId[ls.id] = ls

        // Persisting segments glide to their new rect.
        for ((old, new) in diff.persist) {
            val ls = byId[old.id]
            val (target, cell) = place(new, full)
            if (ls == null) {
                live.add(LiveSegment(new, segmentLayout(new), cell, target))
                continue
            }
            ls.segment = new
            ls.cell = cell
            if (ls.strip == null) { ls.entering = false; ls.clip = null }
            ls.x.animateTo(target.x, moveSpec, now)
            ls.y.animateTo(target.y, moveSpec, now)
            if (ls.alpha.value != 1f || ls.alpha.active) ls.alpha.animateTo(1f, fadeSpec, now)
            if (ls.scale.value != 1f || ls.scale.active) ls.scale.animateTo(1f, fadeSpec, now)
        }

        // Rolling digits: an exit and an enter at the same place value become one odometer strip
        // (old digit, every intermediate digit, new digit) that scrolls inside the digit's cell.
        val rollExits = HashMap<Long, Segment>()
        if (opts.numbers) {
            for (old in diff.exit) {
                if (old.kind != SegmentKind.DIGIT || old.roll == 0) continue
                rollExits[rollKey(old.group ?: continue, old.place ?: continue)] = old
            }
        }
        val claimed = HashSet<Long>()

        // Entering segments appear at their target, faded and (optionally) shrunk; digits roll in.
        for (new in diff.enter) {
            val (target, cell) = place(new, full)
            val layout = segmentLayout(new)
            val roll = if (opts.numbers && new.kind == SegmentKind.DIGIT) new.roll else 0
            val group = new.group
            val place = new.place
            val from = if (roll != 0 && group != null && place != null) rollExits[rollKey(group, place)] else null
            if (from != null) {
                claimed.add(from.id)
                val previous = byId[from.id]
                val previousStrip = previous?.strip
                val ls = LiveSegment(new, layout, cell, target)
                ls.entering = true
                ls.stripRoll = roll
                val strip: List<TextLayoutResult>
                if (previous != null && previousStrip != null && previous.stripRoll == roll) {
                    // The old digit is itself mid-roll: start the new strip at the digit currently on
                    // screen, not the one it was heading to, so nothing blanks out while it catches up.
                    val p = previous.stripProgress.value.coerceIn(0f, (previousStrip.size - 1).toFloat())
                    val k = p.toInt()
                    strip = digitStrip(previousStrip[k].layoutInput.text.text, new.text, roll)
                    ls.stripProgress.snapTo(p - k, previous.stripProgress.velocityAt(now))
                    ls.x.snapTo(previous.x.value); ls.y.snapTo(previous.y.value)
                    ls.x.animateTo(target.x, moveSpec, now); ls.y.animateTo(target.y, moveSpec, now)
                } else {
                    strip = digitStrip(from.text, new.text, roll)
                }
                ls.strip = strip
                ls.stripProgress.animateTo((strip.size - 1).toFloat(), fadeSpec, now)
                live.add(ls)
                continue
            }
            val startY = if (roll != 0) target.y + roll * cell.height else target.y
            val ls = LiveSegment(new, layout, cell, Offset(target.x, startY), if (roll != 0) 1f else 0f, if (roll != 0) 1f else minScale)
            ls.entering = true
            if (roll != 0) ls.clip = cell
            live.add(ls)
            if (roll != 0) ls.y.animateTo(target.y, moveSpec, now)
            ls.alpha.animateTo(1f, fadeSpec, now)
            ls.scale.animateTo(1f, fadeSpec, now)
        }

        // Exiting segments fade (digits roll) out, then leave the list when settled.
        for (old in diff.exit) {
            val ls = byId[old.id] ?: continue
            if (old.id in claimed) {
                live.remove(ls) // its strip successor draws the old digit from here on
                continue
            }
            ls.exiting = true
            ls.entering = false
            ls.strip = null
            ls.stripProgress.snapTo(0f)
            val roll = if (opts.numbers && old.kind == SegmentKind.DIGIT) old.roll else 0
            if (roll != 0) {
                ls.clip = ls.cell
                ls.y.animateTo(ls.y.value - roll * ls.cell.height, moveSpec, now)
            } else {
                ls.alpha.animateTo(0f, fadeSpec, now)
                if (minScale != 1f) ls.scale.animateTo(minScale, fadeSpec, now)
            }
        }

        // Container size.
        if (opts.sizeMode == MorphSizeMode.Snap) {
            width.snapTo(newSize.width); height.snapTo(newSize.height)
        } else {
            width.animateTo(newSize.width, moveSpec, now)
            height.animateTo(newSize.height, moveSpec, now)
        }

        // Exactly one of complete/cancel per morph: a new morph cancels the one in flight.
        finishGeneration(cancelled = true)
        generationAnimating = true
        isAnimating = true
        frameTick.intValue++
        val start = onAnimationStart
        if (start != null) scope.launch { start() }
        ensureLoop()
    }

    private fun finishGeneration(cancelled: Boolean) {
        if (!generationAnimating) return
        generationAnimating = false
        isAnimating = false
        val cb = if (cancelled) onAnimationCancel else onAnimationComplete
        if (cb != null) scope.launch { cb() }
    }

    // endregion

    // region frame loop

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                val active = withFrameNanos { now -> tick(now) }
                if (!active) break
            }
            lastFrameNanos = Channel.UNSET
            finishGeneration(cancelled = false)
        }
    }

    /** Advance every channel to [now]; drop settled exits. Returns true while anything still moves. */
    private fun tick(now: Long): Boolean {
        lastFrameNanos = now
        var active = false
        var i = 0
        while (i < live.size) {
            val ls = live[i]
            val moving = ls.tick(now)
            if (!moving && ls.exiting) {
                live.removeAt(i)
                continue
            }
            active = active || moving
            i++
        }
        val wMoving = width.tick(now)
        val hMoving = height.tick(now)
        val sizeMoving = wMoving || hMoving
        // One extra re-measure after settling so layout picks up the final value.
        if (sizeMoving || lastSizeMoving) sizeTick.intValue++
        lastSizeMoving = sizeMoving
        frameTick.intValue++
        return active || sizeMoving
    }

    // endregion

    // region geometry

    /** Top-left draw offset and the line cell (for clipping rolls) of [s] inside [full]. */
    private fun place(s: Segment, full: TextLayoutResult): Pair<Offset, Rect> {
        if (!TextMorphDiagnostics.logTimings) return placeImpl(s, full)
        val layoutBefore = timings.segmentLayout
        val t0 = System.nanoTime()
        val result = placeImpl(s, full)
        // Exclude nested segment measuring so the two numbers don't double-count.
        timings.place += (System.nanoTime() - t0) - (timings.segmentLayout - layoutBefore)
        timings.placeCalls++
        return result
    }

    private fun placeImpl(s: Segment, full: TextLayoutResult): Pair<Offset, Rect> {
        val textLen = full.layoutInput.text.length
        if (textLen == 0) return Offset.Zero to Rect.Zero
        val start = s.index.coerceIn(0, textLen - 1)
        val boxes = charBoxes(full)
        val line = full.getLineForOffset(start)
        val lineTop = full.getLineTop(line)
        val lineBottom = full.getLineBottom(line)
        val baseline = full.getLineBaseline(line)
        if (s.kind == SegmentKind.NEWLINE) {
            val r = full.getCursorRect(start)
            return Offset(r.left, lineTop) to Rect(r.left, lineTop, r.left, lineBottom)
        }
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        val end = s.end.coerceAtMost(textLen)
        var i = start
        while (i < end) {
            val boxLeft = boxes[4 * i]
            val boxRight = boxes[4 * i + 2]
            if (boxRight - boxLeft > 0f || boxLeft != 0f) {
                if (boxLeft < left) left = boxLeft
                if (boxRight > right) right = boxRight
            }
            i++
        }
        if (left == Float.POSITIVE_INFINITY) {
            val r = full.getCursorRect(start)
            left = r.left; right = r.left
        }
        val seg = segmentLayout(s)
        val y = baseline - seg.firstBaseline
        // Centre the segment's own layout inside the measured box so kerning differences split evenly.
        val x = left + ((right - left) - seg.size.width) / 2f
        return Offset(x, y) to Rect(left, lineTop, right, lineBottom)
    }

    /**
     * Every character's box in [full], filled in one batch on first use. Per-character
     * `getBoundingBox` looked up the line and measured both edges on every call, and allocated two
     * rects; the batch shares each edge between neighbouring characters and allocates nothing.
     *
     * Boxes still held for the previous layout are kept for the leading lines that
     * [reusableBoxPrefix] proves unchanged, so an edit only pays for the lines from the change on.
     */
    private fun charBoxes(full: TextLayoutResult): FloatArray {
        if (charBoxesFor === full) return charBoxes
        val diag = TextMorphDiagnostics.logTimings
        val t0 = if (diag) System.nanoTime() else 0L
        val textLen = full.layoutInput.text.length
        val reused = charBoxesFor?.let { reusableBoxPrefix(it, full) } ?: 0
        // copyOf keeps the reused prefix, which sits at the same indices in the new text.
        if (charBoxes.size < textLen * 4) charBoxes = charBoxes.copyOf(textLen * 4)
        if (reused < textLen) full.multiParagraph.fillBoundingBoxes(TextRange(reused, textLen), charBoxes, reused * 4)
        charBoxesFor = full
        if (diag) { timings.box += System.nanoTime() - t0; timings.boxCalls += textLen - reused }
        return charBoxes
    }

    private fun segmentLayout(s: Segment): TextLayoutResult {
        layoutCache[s.text]?.let { return it }
        val diag = TextMorphDiagnostics.logTimings
        val t0 = if (diag) System.nanoTime() else 0L
        val measured = textMeasurer.measure(
            text = AnnotatedString(if (s.kind == SegmentKind.NEWLINE) "" else s.text),
            style = style,
            softWrap = false,
            maxLines = 1,
        )
        if (diag) { timings.segmentLayout += System.nanoTime() - t0; timings.layoutMisses++ }
        layoutCache[s.text] = measured
        return measured
    }

    /** Layouts for every digit from [from] to [to] moving in [roll] direction (wrapping 9 -> 0), same script as [to]. */
    private fun digitStrip(from: String, to: String, roll: Int): List<TextLayoutResult> {
        val a = Character.getNumericValue(from[0]).coerceIn(0, 9)
        val b = Character.getNumericValue(to[0]).coerceIn(0, 9)
        val zero = to[0] - b
        val steps = if (roll > 0) (b - a + 10) % 10 else (a - b + 10) % 10
        val out = ArrayList<TextLayoutResult>(steps + 1)
        for (k in 0..steps) {
            val d = if (roll > 0) (a + k) % 10 else (a - k + 10) % 10
            val ch = (zero + d).toString()
            out.add(layoutCache.getOrPut(ch) {
                textMeasurer.measure(AnnotatedString(ch), style, softWrap = false, maxLines = 1)
            })
        }
        return out
    }

    private fun rollKey(group: Int, place: Int): Long = (group.toLong() shl 32) or (place.toLong() and 0xFFFFFFFFL)

    // endregion
}

/**
 * Creates and remembers a [TextMorphState]. A new state is created when the text measurer's
 * environment (density, font resolver, layout direction) changes.
 */
@Composable
public fun rememberTextMorphState(animationContext: CoroutineContext = EmptyCoroutineContext): TextMorphState {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // 可选动画上下文在首次组合时接入，默认继续使用宿主时钟。
    val scope = rememberCoroutineScope { animationContext }
    val segmenter = remember<Segmenter> {
        // Android 6 的纯数字时钟使用上游基础分词器，避免加载 API 24 的 ICU 类。
        if (android.os.Build.VERSION.SDK_INT >= 24) IcuSegmenter() else des.c5inco.torph.core.SimpleSegmenter
    }
    return remember(measurer) { TextMorphState(measurer, scope, segmenter) }
}

/**
 * How many leading characters of [new] have exactly the boxes they had in [old]: the whole lines
 * before the first changed character whose start, end, extent and paragraph direction are
 * unchanged. Line breaking can look ahead, and paragraph direction can depend on later text, so this
 * compares the laid-out lines rather than assuming the text before an edit keeps its layout.
 */
internal fun reusableBoxPrefix(old: TextLayoutResult, new: TextLayoutResult): Int {
    val a = old.layoutInput
    val b = new.layoutInput
    if (a.style != b.style || a.constraints != b.constraints || a.layoutDirection != b.layoutDirection ||
        a.density != b.density || a.fontFamilyResolver != b.fontFamilyResolver ||
        a.softWrap != b.softWrap || a.maxLines != b.maxLines || a.overflow != b.overflow
    ) return 0
    val oldText = a.text.text
    val newText = b.text.text
    val limit = minOf(oldText.length, newText.length)
    var common = 0
    while (common < limit && oldText[common] == newText[common]) common++
    var reusable = 0
    for (line in 0 until minOf(old.lineCount, new.lineCount)) {
        val start = new.getLineStart(line)
        val end = new.getLineEnd(line)
        if (end > common) break
        if (old.getLineStart(line) != start || old.getLineEnd(line) != end ||
            old.getLineLeft(line) != new.getLineLeft(line) || old.getLineRight(line) != new.getLineRight(line) ||
            old.getLineTop(line) != new.getLineTop(line) || old.getLineBottom(line) != new.getLineBottom(line) ||
            old.getParagraphDirection(start) != new.getParagraphDirection(start)
        ) break
        reusable = end
    }
    return reusable
}
