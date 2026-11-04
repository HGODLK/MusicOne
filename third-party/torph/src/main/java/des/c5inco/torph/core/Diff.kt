package des.c5inco.torph.core

import java.util.Locale

/**
 * Result of [diffSegments].
 *
 * @property persist Pairs of (old, new) segments that represent the same content. The new segment
 *   carries the old id.
 * @property enter Segments that exist only in the new text.
 * @property exit Segments that exist only in the old text.
 * @property segments The complete new segment list, in order, with ids resolved (persisting
 *   segments keep their old id). Feed this back in as `old` for the next diff.
 * @property nextId First unused id after this diff.
 */
public data class DiffResult(
    val persist: List<Pair<Segment, Segment>>,
    val enter: List<Segment>,
    val exit: List<Segment>,
    val segments: List<Segment>,
    val nextId: Long,
) {
    /** True when nothing entered or exited: every segment persisted. */
    public val isEmpty: Boolean get() = enter.isEmpty() && exit.isEmpty()
}

/**
 * Convenience: segments [newText] then diffs it against [old].
 */
public fun diffSegments(
    old: List<Segment>,
    newText: String,
    locale: Locale = Locale.getDefault(),
    segmenter: Segmenter = SimpleSegmenter,
    options: SegmentOptions = SegmentOptions(),
    nextId: Long = (old.maxOfOrNull { it.id } ?: -1L) + 1,
): DiffResult = diffSegments(old, segmentText(newText, locale, segmenter, options, nextId), locale)

/**
 * Matches [old] against [new] and decides which segments persist, enter, and exit.
 *
 * 1. Numeric words are paired left-to-right by ordinal. Within a pair, digits match by place value
 *    (same digit at the same place persists; a different digit becomes a rolling exit/enter pair)
 *    and symbols match by role (prefix/suffix by position, interior by place).
 * 2. The text runs between paired numeric words are matched with a left-biased longest common
 *    subsequence on segment text, so shared prefixes and suffixes persist.
 *
 * [new] must have fresh ids (all greater than any id in [old]); use [segmentText] with
 * `firstId = previous.nextId`.
 */
public fun diffSegments(
    old: List<Segment>,
    new: List<Segment>,
    locale: Locale = Locale.getDefault(),
): DiffResult {
    val persist = ArrayList<Pair<Segment, Segment>>()
    val enter = ArrayList<Segment>()
    val exit = ArrayList<Segment>()
    // new index -> resolved segment
    val resolved = arrayOfNulls<Segment>(new.size)
    val oldUsed = BooleanArray(old.size)
    val symbols = NumberSymbols.forLocale(locale)

    val oldGroups = groupIndices(old)
    val newGroups = groupIndices(new)
    val paired = minOf(oldGroups.size, newGroups.size)

    // --- 1. numeric words ---
    for (g in 0 until paired) {
        val oi = oldGroups[g]
        val ni = newGroups[g]
        val oldValue = parseNumericWord(oi.joinToString("") { old[it].text }, symbols)
        val newValue = parseNumericWord(ni.joinToString("") { new[it].text }, symbols)
        val roll = if (newValue < oldValue) -1 else 1
        val oldKeys = HashMap<String, Int>()
        for (idx in oi) oldKeys[numberKey(old, oi, idx)] = idx
        for (idx in ni) {
            val key = numberKey(new, ni, idx)
            val match = oldKeys[key]
            val ns = new[idx]
            if (match != null && !oldUsed[match] && old[match].text == ns.text) {
                oldUsed[match] = true
                val kept = ns.copy(id = old[match].id)
                resolved[idx] = kept
                persist.add(old[match] to kept)
            } else if (match != null && !oldUsed[match] && ns.kind == SegmentKind.DIGIT) {
                // Same place, different digit: roll.
                oldUsed[match] = true
                exit.add(old[match].copy(roll = roll))
                val entering = ns.copy(roll = roll)
                resolved[idx] = entering
                enter.add(entering)
            } else {
                val entering = if (ns.kind == SegmentKind.DIGIT) ns.copy(roll = roll) else ns
                resolved[idx] = entering
                enter.add(entering)
            }
        }
        for (idx in oi) if (!oldUsed[idx]) {
            oldUsed[idx] = true
            exit.add(if (old[idx].kind == SegmentKind.DIGIT) old[idx].copy(roll = roll) else old[idx])
        }
    }

    // --- 2. text runs between paired numeric words ---
    val oldRuns = runs(old, oldGroups, paired)
    val newRuns = runs(new, newGroups, paired)
    val textCodes = HashMap<String, Int>()
    for (r in 0..paired) {
        val a = oldRuns[r]
        val b = newRuns[r]
        // The LCS compares every old segment with every new one, so compare int codes, not strings.
        val oldCodes = IntArray(a.size) { sameTextCode(old[a[it]], textCodes) }
        val newCodes = IntArray(b.size) { sameTextCode(new[b[it]], textCodes) }
        val matches = lcs(a, b) { i, j -> oldCodes[i] == newCodes[j] }
        val bMatched = BooleanArray(b.size)
        for ((i, j) in matches) {
            val o = old[a[i]]
            val kept = new[b[j]].copy(id = o.id)
            resolved[b[j]] = kept
            oldUsed[a[i]] = true
            bMatched[j] = true
            persist.add(o to kept)
        }
        for (j in b.indices) if (!bMatched[j]) {
            resolved[b[j]] = new[b[j]]
            enter.add(new[b[j]])
        }
        for (i in a.indices) if (!oldUsed[a[i]]) {
            oldUsed[a[i]] = true
            exit.add(old[a[i]])
        }
    }

    val segments = resolved.map { it!! }
    val nextId = (segments.maxOfOrNull { it.id } ?: -1L).coerceAtLeast(old.maxOfOrNull { it.id } ?: -1L) + 1
    return DiffResult(persist, enter, exit, segments, nextId)
}

/**
 * Two segments get the same code when their text matches and neither or both are line breaks.
 * Kind is otherwise ignored, so unpaired numeric words fall back to plain text matching.
 */
private fun sameTextCode(s: Segment, codes: HashMap<String, Int>): Int {
    val code = codes.getOrPut(s.text) { codes.size + 1 }
    return if (s.kind == SegmentKind.NEWLINE) -code else code
}

/** Indices of segments per numeric group, in group order. */
private fun groupIndices(segments: List<Segment>): List<IntArray> {
    val map = LinkedHashMap<Int, MutableList<Int>>()
    segments.forEachIndexed { i, s -> s.group?.let { map.getOrPut(it) { ArrayList() }.add(i) } }
    return map.values.map { it.toIntArray() }
}

/** Matching key for a segment within its numeric word. */
private fun numberKey(segments: List<Segment>, group: IntArray, idx: Int): String {
    val s = segments[idx]
    if (s.kind == SegmentKind.DIGIT) return "d${s.place}"
    val firstDigit = group.indexOfFirst { segments[it].kind == SegmentKind.DIGIT }
    val lastDigit = group.indexOfLast { segments[it].kind == SegmentKind.DIGIT }
    val posInGroup = group.indexOf(idx)
    return when {
        firstDigit < 0 -> "s$posInGroup:${s.text}"
        posInGroup < firstDigit -> "pre${posInGroup}:${s.text}"
        posInGroup > lastDigit -> "suf${group.size - 1 - posInGroup}:${s.text}"
        else -> "mid${s.place}:${s.text}"
    }
}

/** Segment indices for the runs before group 0, between consecutive paired groups, and after group paired-1. */
private fun runs(segments: List<Segment>, groups: List<IntArray>, paired: Int): List<IntArray> {
    val pairedIdx = HashSet<Int>()
    for (g in 0 until paired) for (i in groups[g]) pairedIdx.add(i)
    val out = ArrayList<IntArray>(paired + 1)
    var current = ArrayList<Int>()
    var g = 0
    for (i in segments.indices) {
        if (i in pairedIdx) {
            // The first index of the next paired group closes the current run.
            if (g < paired && i == groups[g][0]) {
                out.add(current.toIntArray()); current = ArrayList(); g++
            }
            continue
        }
        current.add(i)
    }
    out.add(current.toIntArray())
    while (out.size < paired + 1) out.add(IntArray(0))
    return out
}

/**
 * Left-biased LCS: returns matched (i, j) pairs in increasing order. Built from a suffix table and
 * walked from the front, so among equal-length subsequences the earliest possible matches win.
 *
 * Inline so [eq] is called without boxing its indices: it runs n * m times, and a boxed call
 * allocated two `Integer`s per cell once indices passed 127 (16 MB for a 1000-segment diff).
 */
internal inline fun lcs(a: IntArray, b: IntArray, eq: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
    val n = a.size
    val m = b.size
    if (n == 0 || m == 0) return emptyList()
    val out = ArrayList<Pair<Int, Int>>()
    // The walk below always matches a shared prefix pairwise, so take it without building the table.
    var p = 0
    while (p < n && p < m && eq(p, p)) { out.add(p to p); p++ }
    if (p == n || p == m) return out
    // dp[(i - p) * w + (j - p)] is the LCS length of a[i..] and b[j..]; one flat array, not one per row.
    val w = m - p + 1
    val dp = IntArray((n - p + 1) * w)
    for (i in n - 1 downTo p) {
        val row = (i - p) * w
        for (j in m - 1 downTo p) {
            val c = row + (j - p)
            dp[c] = if (eq(i, j)) dp[c + w + 1] + 1 else maxOf(dp[c + w], dp[c + 1])
        }
    }
    var i = p
    var j = p
    while (i < n && j < m) {
        val c = (i - p) * w + (j - p)
        when {
            eq(i, j) && dp[c] == dp[c + w + 1] + 1 -> { out.add(i to j); i++; j++ }
            dp[c + w] >= dp[c + 1] -> i++
            else -> j++
        }
    }
    return out
}
