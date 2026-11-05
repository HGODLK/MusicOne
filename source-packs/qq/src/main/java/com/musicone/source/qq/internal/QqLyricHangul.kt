package com.musicone.source.qq.internal

private const val HANGUL_FIRST = '가'
private const val HANGUL_LAST = '힣'
private val HANGUL_VOWELS = "a ae ya yae eo e yeo ye o wa wae oe yo u wo we wi yu eu ui i".split(' ')

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
 * QQ 韩语罗马音按词给出，同一词内的音节用空格分开。
 * 词数、音节数或元音对不上时整句不显示注音；时间在词内平分。
 */
internal fun qqHangulTransliteration(words: List<LyricWord>, romaji: List<LyricWord>): String? {
    if (words.isEmpty() || words.size != romaji.size || words.any { ':' in it.text || '：' in it.text }) return null
    val segments = ArrayList<String>(words.size)
    for (index in words.indices) {
        val syllables = words[index].text.filter { it.isHangul() }
        if (syllables.isEmpty()) {
            segments += ""
            continue
        }
        val tokens = romajiMoras(romaji[index].text)
        if (tokens.size != syllables.length) return null
        if (tokens.indices.any { !hangulReadingMatches(syllables[it], tokens[it].text) }) return null
        val source = romaji[index]
        val duration = (source.endMs - source.startMs).coerceAtLeast(0L) / tokens.size
        segments += tokens.indices.joinToString(" ") { token ->
            encodeMora(RomajiMora(standardHangulSpelling(tokens[token].text), source.startMs + duration * token, duration),
                words[index].startMs)
        }
    }
    return segments.joinToString("\t").takeIf { segments.any(String::isNotEmpty) }
}

/** 元音基本不随发音规则变化，用来发现错位；네 常唱成 ni，의 也可读 i 或 e。 */
private fun hangulReadingMatches(syllable: Char, reading: String): Boolean {
    val vowel = HANGUL_VOWELS[((syllable - HANGUL_FIRST) % 588) / 28]
    return vowel in reading || syllable == '네' && reading == "ni" ||
        vowel == "ui" && ('i' in reading || 'e' in reading)
}

/** QQ 把紧音写成 gg/dd/bb，统一成标准写法，和酷狗显示一致。 */
private fun standardHangulSpelling(reading: String): String =
    reading.replace("gg", "kk").replace("dd", "tt").replace("bb", "pp")
