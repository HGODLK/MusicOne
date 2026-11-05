package com.musicone.source.kugou

private const val HANGUL_FIRST = '가'
private const val HANGUL_LAST = '힣'
private const val VOWEL_ONSET = ""

private val HANGUL_ONSETS = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", VOWEL_ONSET,
    "j", "jj", "ch", "k", "t", "p", "h")
private val HANGUL_VOWELS = "a ae ya yae eo e yeo ye o wa wae oe yo u wo we wi yu eu ui i".split(' ')
private val HANGUL_CODAS = " ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ".split(' ')

// 辅音前和词尾的实际读音。
private val CODA_SOUND = mapOf("" to "", "ㄱ" to "k", "ㄲ" to "k", "ㄳ" to "k", "ㄴ" to "n", "ㄵ" to "n", "ㄶ" to "n",
    "ㄷ" to "t", "ㄹ" to "l", "ㄺ" to "k", "ㄻ" to "m", "ㄼ" to "l", "ㄽ" to "l", "ㄾ" to "l", "ㄿ" to "p", "ㅀ" to "l",
    "ㅁ" to "m", "ㅂ" to "p", "ㅄ" to "p", "ㅅ" to "t", "ㅆ" to "t", "ㅇ" to "ng", "ㅈ" to "t", "ㅊ" to "t", "ㅋ" to "k",
    "ㅌ" to "t", "ㅍ" to "p", "ㅎ" to "t")

// 连音：元音前留下的收音，以及移给下一音节的声母。
private val CODA_LIAISON = mapOf("" to ("" to ""), "ㄱ" to ("" to "g"), "ㄲ" to ("" to "kk"), "ㄳ" to ("k" to "s"),
    "ㄴ" to ("" to "n"), "ㄵ" to ("n" to "j"), "ㄶ" to ("" to "n"), "ㄷ" to ("" to "d"), "ㄹ" to ("" to "r"),
    "ㄺ" to ("l" to "g"), "ㄻ" to ("l" to "m"), "ㄼ" to ("l" to "b"), "ㄽ" to ("l" to "s"), "ㄾ" to ("l" to "t"),
    "ㄿ" to ("l" to "p"), "ㅀ" to ("" to "r"), "ㅁ" to ("" to "m"), "ㅂ" to ("" to "b"), "ㅄ" to ("p" to "s"),
    "ㅅ" to ("" to "s"), "ㅆ" to ("" to "ss"), "ㅇ" to ("ng" to ""), "ㅈ" to ("" to "j"), "ㅊ" to ("" to "ch"),
    "ㅋ" to ("" to "k"), "ㅌ" to ("" to "t"), "ㅍ" to ("" to "p"), "ㅎ" to ("" to ""))

private val ASPIRATED = mapOf("g" to "k", "d" to "t", "j" to "ch", "b" to "p")
private val STOP_NASAL = mapOf("k" to "ng", "t" to "n", "p" to "m")

internal fun Char.isHangul() = this in HANGUL_FIRST..HANGUL_LAST

/** 按字符比例判断是否韩语歌：要先于日语判断，韩语歌里夹一点假名时不能被当成日语。 */
internal fun lyricHangulLanguageAllowed(lines: List<String>): Boolean {
    var hangul = 0
    var other = 0
    lines.forEach { line ->
        line.forEach { if (it.isHangul()) hangul++ else if (it in 'ぁ'..'ヶ' || it in '㐀'..'鿿') other++ }
    }
    return hangul > 0 && hangul * 2 >= hangul + other
}

/**
 * 酷狗的韩语音译是汉字空耳，不能当注音，这里直接由韩文生成 transliteration。
 * 整句连起来算读音，逐音节拆开的词之间也能正确连音；不生成不属于演唱内容的署名行。
 */
internal fun lyricHangulTransliteration(words: List<String>): String? {
    if (words.isEmpty() || words.any { ':' in it || '：' in it }) return null
    val readings = hangulReadings(words.joinToString(""))
    var consumed = 0
    val segments = words.map { word ->
        val count = word.count { it.isHangul() }
        readings.subList(consumed, consumed + count).joinToString(" ").also { consumed += count }
    }
    return segments.joinToString("\t").takeIf { segments.any(String::isNotEmpty) }
}

/** 每个韩文音节一个读音；空白和其他字符会断开语段，发音变化不跨语段。 */
internal fun hangulReadings(text: String): List<String> {
    val readings = ArrayList<String>(text.length)
    var start = -1
    for (index in 0..text.length) {
        val hangul = index < text.length && text[index].isHangul()
        if (hangul && start < 0) start = index
        if (!hangul && start >= 0) {
            readings += hangulRunReadings(text.substring(start, index))
            start = -1
        }
    }
    return readings
}

private fun hangulRunReadings(run: String): List<String> {
    // “你的”在歌里通常唱成 ni，避免和“我的” 내 混淆。
    if (run == "네") return listOf("ni")
    if (run == "네가") return listOf("ni", "ga")
    val onsets = run.mapTo(ArrayList(run.length)) { HANGUL_ONSETS[(it - HANGUL_FIRST) / 588] }
    return run.indices.map { index ->
        val offset = run[index] - HANGUL_FIRST
        val tail = HANGUL_CODAS[offset % 28]
        val coda = if (index == run.lastIndex) CODA_SOUND.getValue(tail)
            else hangulCoda(tail, run[index + 1], onsets, index + 1)
        onsets[index] + HANGUL_VOWELS[(offset % 588) / 28] + coda
    }
}

/** 计算收音读音，并按连音、腭化、送气化、鼻音化、流音化改写下一音节的声母；紧音不标。 */
private fun hangulCoda(tail: String, next: Char, onsets: MutableList<String>, nextIndex: Int): String {
    val onset = onsets[nextIndex]
    val sound = CODA_SOUND.getValue(tail)
    if (onset == VOWEL_ONSET) {
        val (kept, moved) = CODA_LIAISON.getValue(tail)
        val palatal = HANGUL_VOWELS[((next - HANGUL_FIRST) % 588) / 28] == "i"
        onsets[nextIndex] = when {
            palatal && tail == "ㄷ" -> "j"
            palatal && (tail == "ㅌ" || tail == "ㄾ") -> "ch"
            else -> moved
        }
        return kept
    }
    if (tail == "ㅎ" || tail == "ㄶ" || tail == "ㅀ") {
        val kept = when (tail) { "ㅎ" -> ""; "ㄶ" -> "n"; else -> "l" }
        ASPIRATED[onset]?.let { onsets[nextIndex] = it; return kept }
        return when (onset) {
            "s" -> { onsets[nextIndex] = "ss"; kept }
            "n" -> when (tail) {
                "ㅎ" -> "n"
                "ㅀ" -> { onsets[nextIndex] = "l"; "l" }
                else -> "n"
            }
            else -> kept
        }
    }
    if (onset == "h") {
        if (tail == "ㄵ") { onsets[nextIndex] = "ch"; return "n" }
        if (sound in STOP_NASAL) {
            val palatal = HANGUL_VOWELS[((next - HANGUL_FIRST) % 588) / 28] == "i"
            onsets[nextIndex] = if (palatal && (tail == "ㅈ" || tail == "ㅊ" || tail == "ㄷ")) "ch" else sound
            return if (tail == "ㄺ" || tail == "ㄼ") "l" else ""
        }
        return sound
    }
    return when (onset) {
        "n", "m" -> {
            if (sound == "l" && onset == "n") onsets[nextIndex] = "l"
            STOP_NASAL[sound] ?: sound
        }
        "r" -> when (sound) {
            "n", "l" -> { onsets[nextIndex] = "l"; "l" }
            "m", "ng" -> { onsets[nextIndex] = "n"; sound }
            "k", "t", "p" -> { onsets[nextIndex] = "n"; STOP_NASAL.getValue(sound) }
            else -> sound
        }
        else -> sound
    }
}
