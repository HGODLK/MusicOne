package com.musicone.source.kugou

/** 一个音；时间是歌曲时间线上的绝对毫秒，来源没有逐音时间时为 null。 */
internal data class RomajiMora(val text: String, val startMs: Long? = null, val durationMs: Long? = null)

private enum class RomajiKind { NONE, LATIN, KANA, FREE }
private class RomajiGroup(val first: Int, var last: Int, var kind: RomajiKind, var core: String)

private val ROMAJI_TOKEN = Regex("[A-Za-z]+")
private const val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"
private val KANA_READINGS: Map<Char, Set<String>> = buildMap {
    val kana = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔゐゑ"
    val roman = ("a i u e o ka ki ku ke ko sa shi su se so ta chi tsu te to na ni nu ne no ha hi fu he ho ma mi mu me mo " +
        "ya yu yo ra ri ru re ro wa wo n ga gi gu ge go za ji zu ze zo da ji zu de do ba bi bu be bo pa pi pu pe po vu i e").split(' ')
    kana.forEachIndexed { index, char -> put(char, setOf(roman[index])) }
    // 助词读音和训令式写法都是音源里实际出现的合法写法。
    mapOf('は' to "wa", 'へ' to "e", 'を' to "o", 'ふ' to "hu", 'し' to "si", 'ち' to "ti", 'つ' to "tu",
        'じ' to "zi", 'ぢ' to "di", 'づ' to "du", 'ん' to "m", 'ゐ' to "wi", 'ゑ' to "we")
        .forEach { (char, alternative) -> put(char, getValue(char) + alternative) }
}
private val SMALL_READINGS = mapOf('ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'ぁ' to "a", 'ぃ' to "i",
    'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ゎ' to "wa")

private fun Char.isKanaLetter() = this in 'ぁ'..'ゖ' || this in 'ァ'..'ヶ' || this == 'ー'
private fun Char.isHan() = this in '㐀'..'鿿' || this == '々'
private fun Char.toHiragana() = if (this in 'ァ'..'ヶ') this - 0x60 else this

/** 按字符比例判断是否日文歌：标题里夹一点假名的中文歌不生成注音。 */
internal fun lyricRomajiLanguageAllowed(lines: List<String>): Boolean {
    var kana = 0
    var han = 0
    lines.forEach { line -> line.forEach { if (it.isKanaLetter()) kana++ else if (it.isHan()) han++ } }
    return kana > 0 && kana >= (kana + han) * .15f
}

internal fun romajiMoras(text: String): List<RomajiMora> =
    ROMAJI_TOKEN.findAll(text).map { RomajiMora(it.value.lowercase()) }.toList()

/**
 * 生成 SourceLyricLine.transliteration：制表符分隔，段数等于 words。
 * 空段无注音；"+" 与前一词合标；其他段是空格分隔的音，可带 "@相对字组起点偏移+时长"。
 * 校验不通过时返回 null，宁可这句不显示注音，也不显示错位的读音。
 */
internal fun lyricRomajiTransliteration(words: List<String>, wordStarts: List<Long>,
    moras: List<List<RomajiMora>>): String? {
    if (words.isEmpty() || words.size != moras.size || words.size != wordStarts.size) return null
    // 词曲署名行不是演唱内容。
    if (words.any { ':' in it || '：' in it }) return null
    val groups = romajiGroups(words)
    if (groups.none { it.kind == RomajiKind.KANA || it.kind == RomajiKind.FREE }) return null
    val assigned = directAssignment(groups, moras) ?: anchoredAssignment(groups, moras) ?: return null
    val segments = MutableList(words.size) { "" }
    assigned.forEach { (group, readings) ->
        if ((group.kind != RomajiKind.KANA && group.kind != RomajiKind.FREE) || readings.isEmpty()) return@forEach
        segments[group.first] = readings.joinToString(" ") { mora -> encodeMora(mora, wordStarts[group.first]) }
        for (index in group.first + 1..group.last) segments[index] = "+"
    }
    return segments.joinToString("\t").takeIf { segments.any(String::isNotEmpty) }
}

private fun encodeMora(mora: RomajiMora, groupStartMs: Long): String {
    val start = mora.startMs ?: return mora.text
    val duration = mora.durationMs ?: return mora.text
    return "${mora.text}@${(start - groupStartMs).coerceAtLeast(0L)}+${duration.coerceAtLeast(0L)}"
}

/** 小假名和长音并入前词，促音并入后词，它们本身不是独立的音。 */
private fun romajiGroups(words: List<String>): List<RomajiGroup> {
    val groups = ArrayList<RomajiGroup>(words.size)
    words.forEachIndexed { index, word ->
        val core = word.filter { it.isLetterOrDigit() || it == 'ー' }
        val kind = when {
            core.isEmpty() -> RomajiKind.NONE
            core.all(Char::isKanaLetter) -> RomajiKind.KANA
            core.any { it.isKanaLetter() || it.isHan() } -> RomajiKind.FREE
            else -> RomajiKind.LATIN
        }
        val previous = groups.lastOrNull()
        val pronounced = previous?.kind == RomajiKind.KANA || previous?.kind == RomajiKind.FREE
        val joinsBack = pronounced && kind != RomajiKind.NONE && kind != RomajiKind.LATIN &&
            (core[0] in SMALL_KANA || core[0] == 'ー')
        val joinsSokuon = pronounced && previous.kind == RomajiKind.KANA && previous.core.last().toHiragana() == 'っ' &&
            (kind == RomajiKind.KANA || kind == RomajiKind.FREE)
        if (previous != null && (joinsBack || joinsSokuon)) {
            previous.last = index
            previous.core += core
            if (kind == RomajiKind.FREE) previous.kind = RomajiKind.FREE
        } else groups += RomajiGroup(index, index, kind, core)
    }
    return groups
}

/** 先信任音源给的逐词对应，只用假名的固定读音校验。 */
private fun directAssignment(groups: List<RomajiGroup>, moras: List<List<RomajiMora>>): List<Pair<RomajiGroup, List<RomajiMora>>>? {
    val result = ArrayList<Pair<RomajiGroup, List<RomajiMora>>>(groups.size)
    for (group in groups) {
        val readings = (group.first..group.last).flatMap { moras[it] }
        when (group.kind) {
            RomajiKind.NONE -> if (readings.isNotEmpty()) return null
            RomajiKind.LATIN -> Unit
            RomajiKind.KANA -> if (consumeKana(group.core, readings.map { it.text }, 0) != readings.size || readings.isEmpty()) return null
            RomajiKind.FREE -> if (readings.isEmpty()) {
                // 音源把读音整个给了相邻汉字时，两个字共用一个注音。
                val previous = result.lastOrNull() ?: return null
                if (previous.first.kind != RomajiKind.FREE || previous.first.last + 1 != group.first) return null
                previous.first.last = group.last
                continue
            }
        }
        result += group to readings
    }
    return result
}

/** 逐词对应错位时摊平读音，以假名为锚点重新分配；相邻汉字无法再细分，合标为一组。 */
private fun anchoredAssignment(groups: List<RomajiGroup>, moras: List<List<RomajiMora>>): List<Pair<RomajiGroup, List<RomajiMora>>>? {
    val blocks = ArrayList<RomajiGroup>(groups.size)
    groups.forEach { group ->
        val previous = blocks.lastOrNull()
        if (group.kind == RomajiKind.FREE && previous?.kind == RomajiKind.FREE) previous.last = group.last
        else blocks += group
    }
    val flat = moras.flatten()
    val tokens = flat.map { it.text }
    val ends = IntArray(blocks.size)
    val failed = HashSet<Long>()
    fun walk(block: Int, from: Int): Boolean {
        if (block == blocks.size) return from == tokens.size
        val key = block.toLong() * 100_000L + from
        if (key in failed) return false
        val current = blocks[block]
        val candidates = when (current.kind) {
            RomajiKind.NONE -> from..from
            RomajiKind.LATIN -> from..tokens.size
            RomajiKind.KANA -> consumeKana(current.core, tokens, from)?.let { it..it } ?: IntRange.EMPTY
            RomajiKind.FREE -> from + 1..tokens.size
        }
        for (end in candidates) {
            ends[block] = end
            if (walk(block + 1, end)) return true
        }
        failed += key
        return false
    }
    if (!walk(0, 0)) return null
    var start = 0
    return blocks.mapIndexed { index, block -> (block to flat.subList(start, ends[index])).also { start = ends[index] } }
}

/** 按假名读音消费音节，返回结束位置；读音对不上时返回 null。 */
private fun consumeKana(core: String, tokens: List<String>, from: Int): Int? {
    var cursor = from
    var index = 0
    while (index < core.length) {
        val char = core[index].toHiragana()
        val next = core.getOrNull(index + 1)?.toHiragana()
        when {
            char == 'っ' -> {
                // 促音可能是独立的 t，也可能已并入下一音的 tte。
                val token = tokens.getOrNull(cursor)
                if (token != null && token.length == 1 && token[0] !in "aiueon") cursor++
                index++
            }
            char == 'ー' -> {
                if (tokens.getOrNull(cursor)?.let { it.length == 1 && it[0] in "aiueo" } == true) cursor++
                index++
            }
            else -> {
                val combined = next?.let { SMALL_READINGS[it] }
                val token = tokens.getOrNull(cursor) ?: return null
                val readings = if (combined != null && char in KANA_READINGS) combinedReadings(KANA_READINGS.getValue(char), combined)
                    else KANA_READINGS[char] ?: SMALL_READINGS[char]?.let(::setOf) ?: return null
                if (!readings.any { kanaReadingMatches(token, it) }) return null
                cursor++
                index += if (combined != null && char in KANA_READINGS) 2 else 1
            }
        }
    }
    return cursor
}

private fun combinedReadings(heads: Set<String>, small: String): Set<String> = heads.flatMap { head ->
    val stem = head.dropLast(1)
    listOf(head + small, stem + small, stem + small.last(), head.take(1) + small)
}.toSet()

/** 促音并入时首辅音重复（tte）或写成 tch。 */
private fun kanaReadingMatches(token: String, reading: String): Boolean =
    token == reading || token.length == reading.length + 1 && token.substring(1) == reading &&
        (token[0] == reading[0] || token[0] == 't' && reading.startsWith("ch"))
