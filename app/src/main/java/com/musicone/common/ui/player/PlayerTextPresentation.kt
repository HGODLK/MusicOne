package com.musicone

internal data class PlayerTextPresentation(val title: String, val artists: String)

internal fun MusicTrack.playerTextPresentation() = PlayerTextPresentation(
    title = playerPrimaryTitle(title),
    artists = playerPrimaryArtists(artists),
)

private val trailingPlayerTitleDetails = Regex("(?:\\s*[（(][^()（）]*[）)])+\\s*$")
private val playerParentheticalDetails = Regex("\\s*(?:\\([^()（）]*\\)|（[^()（）]*）)")
// 覆盖平假名、全角及半角片假名，不把括号内的纯中文或英文当作读音。
private val playerKana = Regex("[ぁ-ゖゝ-ゟァ-ヺヽ-ヿㇰ-ㇿｦ-ﾟ]")
// 文字类别必须写 \p{script=Xxx}：Android 9 的 ICU 不认 \p{IsXxx}，类加载即闪退。
private val playerJapaneseName = Regex("[\\p{script=Han}ぁ-ゖァ-ヺｦ-ﾟ]")
private val playerLatinName = Regex("[\\p{script=Latin}]+(?:[\\s.'’·-]+[\\p{script=Latin}]+)*")
private val playerArtistSeparator = Regex("[、/／,，;&＆]")
private val playerArtistVersion = Regex("(?i)\\b(live|remix|version|ver|edit|mix|feat|ft|dj)\\b")

/** 播放器只展示主歌名，平台返回的末尾翻译、片假名和版本括注留在列表详情中。 */
internal fun playerPrimaryTitle(title: String): String {
    val fullTitle = playerTextWithoutKanaAnnotations(stripEdgeBidiControls(title))
    return stripEdgeBidiControls(fullTitle.replace(trailingPlayerTitleDetails, "")).trimEnd().ifBlank { fullTitle }
}

/** 多位创作者逐个去掉读音括注，保留原名、连接符和其他括号内容；混排 RTL 创作者时隔离字段避免倒序。 */
internal fun playerPrimaryArtists(artists: String): String {
    val original = stripEdgeBidiControls(artists).trim()
    val cleaned = original.replace(playerParentheticalDetails) { match ->
        val annotation = match.value.trim().drop(1).dropLast(1).trim()
        // 只将当前创作者非拉丁原名后的独立拉丁括注视为读音，保留英文艺名中的括号。
        val name = original.substring(0, match.range.first)
            .split(playerArtistSeparator).last().substringBefore('(').substringBefore('（').trim()
        val following = original.substring(match.range.last + 1).trimStart()
        val standalone = following.isEmpty() || following.startsWith('(') || following.startsWith('（') ||
            playerArtistSeparator.containsMatchIn(following.take(1))
        val romanized = standalone && playerJapaneseName.containsMatchIn(name) &&
            playerLatinName.matches(annotation) && !playerArtistVersion.containsMatchIn(annotation)
        if (playerKana.containsMatchIn(annotation) || romanized) "" else match.value
    }.trim().ifBlank { original }
    return isolateMixedArtists(cleaned)
}

private fun stripEdgeBidiControls(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && (text[start].isWhitespace() || isBidiControlChar(text[start]))) start++
    while (end > start && (text[end - 1].isWhitespace() || isBidiControlChar(text[end - 1]))) end--
    return text.substring(start, end)
}

private fun isolateMixedArtists(text: String): String {
    if (text.isEmpty() || !(containsArabicOrPersian(text) || isRtlStrongText(text))) return text
    val matches = playerArtistSeparator.findAll(text).toList()
    if (matches.isEmpty()) return text
    val builder = StringBuilder(text.length + (matches.size + 1) * 2)
    var cursor = 0
    for (match in matches) {
        appendIsolatedArtistPart(builder, text.substring(cursor, match.range.first))
        builder.append(match.value)
        cursor = match.range.last + 1
    }
    appendIsolatedArtistPart(builder, text.substring(cursor))
    return builder.toString()
}

private fun appendIsolatedArtistPart(builder: StringBuilder, segment: String) {
    val trimmed = segment.trim()
    if (trimmed.isEmpty()) {
        builder.append(segment)
        return
    }
    val lead = segment.indexOf(trimmed)
    val trail = lead + trimmed.length
    builder.append(segment, 0, lead)
    builder.append(BIDI_FSI).append(trimmed).append(BIDI_PDI)
    builder.append(segment, trail, segment.length)
}

private fun playerTextWithoutKanaAnnotations(text: String): String {
    val original = text.trim()
    return original.replace(playerParentheticalDetails) { match ->
        if (playerKana.containsMatchIn(match.value)) "" else match.value
    }.trim().ifBlank { original }
}
