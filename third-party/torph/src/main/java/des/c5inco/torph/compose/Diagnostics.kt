package des.c5inco.torph.compose

import android.util.Log

/**
 * Opt-in timing breakdown for the work [TextMorph] does when the text changes.
 *
 * Text changes cost one expensive frame; everything else is draw-only. Turning this on logs where
 * that frame's time actually goes, one line per change, under the tag [TAG]:
 *
 * ```
 * adb logcat -s TextMorphPerf
 * ```
 *
 * Off by default and effectively free when off (one boolean read per phase). Leave it off in
 * production: when on it calls [System.nanoTime] around every segment placement.
 */
public object TextMorphDiagnostics {
    /** Log a phase breakdown for every text change. */
    public var logTimings: Boolean = false

    /** Logcat tag for the breakdown lines. */
    public const val TAG: String = "TextMorphPerf"
}

/**
 * Nanosecond accumulators for one text change. Written only from the layout pass, so no
 * synchronisation. [place] excludes the segment-layout measures nested inside it, and [box]
 * is the per-character bounding-box scan inside placement.
 */
internal class MorphTimings {
    var measureText = 0L
    var segment = 0L
    var diff = 0L
    var place = 0L
    var box = 0L
    var segmentLayout = 0L
    var animation = 0L

    var boxCalls = 0
    var placeCalls = 0
    var layoutMisses = 0

    fun reset() {
        measureText = 0; segment = 0; diff = 0; place = 0; box = 0; segmentLayout = 0; animation = 0
        boxCalls = 0; placeCalls = 0; layoutMisses = 0
    }

    fun log(totalNs: Long, chars: Int, segments: Int, persist: Int, enter: Int, exit: Int) {
        Log.d(
            TextMorphDiagnostics.TAG,
            "change chars=$chars segs=$segments (p=$persist e=$enter x=$exit) " +
                "total=${ms(totalNs)} | measureText=${ms(measureText)} segment=${ms(segment)} " +
                "diff=${ms(diff)} place=${ms(place)} (box=${ms(box)} over $boxCalls chars, " +
                "$placeCalls calls) segLayout=${ms(segmentLayout)} ($layoutMisses miss) " +
                "anim=${ms(animation)}",
        )
    }

    private fun ms(ns: Long): String = "%.2fms".format(ns / 1_000_000.0)
}
