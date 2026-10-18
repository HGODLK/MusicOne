package com.musicone.demo

/** 歌词页仅为可编辑音源开放资料菜单，展开、收起和拖动期间继续防止误触。 */
internal fun playerInformationMenuEnabled(
    source: MusicSource,
    lyrics: Boolean,
    lyricsProgress: Float,
    headerProgress: Float,
    dragging: Boolean,
): Boolean = !dragging && if (lyrics) {
    source.supportsLibraryEditing && lyricsProgress == 1f && headerProgress == 1f
} else {
    lyricsProgress == 0f && headerProgress == 0f
}
