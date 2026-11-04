package com.musicone

/** 歌词页按歌词权限开放菜单，展开、收起和拖动期间继续防止误触。 */
internal fun playerInformationMenuEnabled(
    source: MusicSource,
    lyrics: Boolean,
    lyricsProgress: Float,
    headerProgress: Float,
    dragging: Boolean,
    supportsLyricsActions: Boolean = source.supportsManualLyrics || source.supportsLyricOffset,
): Boolean = !dragging && if (lyrics) {
    supportsLyricsActions && lyricsProgress == 1f && headerProgress == 1f
} else {
    lyricsProgress == 0f && headerProgress == 0f
}

/** 手机歌词开启后，歌手和整块资料热区均不打开关联菜单。 */
internal fun phonePlayerInformationMenuEnabled(
    source: MusicSource,
    lyrics: Boolean,
    lyricsProgress: Float,
    headerProgress: Float,
    dragging: Boolean,
): Boolean = !lyrics && playerInformationMenuEnabled(source, lyrics, lyricsProgress, headerProgress, dragging)
