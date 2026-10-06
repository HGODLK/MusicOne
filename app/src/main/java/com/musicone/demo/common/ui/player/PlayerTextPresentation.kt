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

/** 播放器只展示主歌名，平台返回的末尾翻译、片假名和版本括注留在列表详情中。 */
internal fun playerPrimaryTitle(title: String): String {
    val fullTitle = playerTextWithoutKanaAnnotations(title)
    return fullTitle.replace(trailingPlayerTitleDetails, "").trimEnd().ifBlank { fullTitle }
}

/** 多位创作者逐个去掉读音括注，保留原名、连接符和其他括号内容。 */
internal fun playerPrimaryArtists(artists: String): String = playerTextWithoutKanaAnnotations(artists)

private fun playerTextWithoutKanaAnnotations(text: String): String {
    val original = text.trim()
    return original.replace(playerParentheticalDetails) { match ->
        if (playerKana.containsMatchIn(match.value)) "" else match.value
    }.trim().ifBlank { original }
}
