package com.musicone.demo

internal data class PlayerTextPresentation(val title: String, val artists: String)

internal fun MusicTrack.playerTextPresentation() = PlayerTextPresentation(
    title = playerPrimaryTitle(title),
    artists = playerPrimaryArtists(artists),
)

private val trailingPlayerTitleDetails = Regex("(?:\\s*[（(][^()（）]*[）)])+\\s*$")
private val playerParentheticalDetails = Regex("\\s*(?:\\([^()（）]*\\)|（[^()（）]*）)")
// 覆盖平假名、全角及半角片假名，不把括号内的纯中文或英文当作读音。
private val playerKana = Regex("[ぁ-ゖゝ-ゟァ-ヺヽ-ヿㇰ-ㇿｦ-ﾟ]")
private val playerJapaneseName = Regex("[\\p{IsHan}ぁ-ゖァ-ヺｦ-ﾟ]")
private val playerLatinName = Regex("[\\p{IsLatin}]+(?:[\\s.'’·-]+[\\p{IsLatin}]+)*")
private val playerArtistSeparator = Regex("[、/／,，;&＆]")
private val playerArtistVersion = Regex("(?i)\\b(live|remix|version|ver|edit|mix|feat|ft|dj)\\b")

/** 播放器只展示主歌名，平台返回的末尾翻译、片假名和版本括注留在列表详情中。 */
internal fun playerPrimaryTitle(title: String): String {
    val fullTitle = playerTextWithoutKanaAnnotations(title)
    return fullTitle.replace(trailingPlayerTitleDetails, "").trimEnd().ifBlank { fullTitle }
}

/** 多位创作者逐个去掉读音括注，保留原名、连接符和其他括号内容。 */
internal fun playerPrimaryArtists(artists: String): String {
    val original = artists.trim()
    return original.replace(playerParentheticalDetails) { match ->
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
}

private fun playerTextWithoutKanaAnnotations(text: String): String {
    val original = text.trim()
    return original.replace(playerParentheticalDetails) { match ->
        if (playerKana.containsMatchIn(match.value)) "" else match.value
    }.trim().ifBlank { original }
}
