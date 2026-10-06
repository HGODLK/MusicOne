package com.musicone.demo

private data class QqLibraryCardPresentation(
    val id: String,
    val source: MusicSource,
    val title: String,
    val subtitle: String,
    val count: Int,
    val artworkStart: Long,
    val artworkEnd: Long,
    val artworkMark: String,
    val artworkUrl: String?,
)

internal fun MusicPlaylist.hasSameLibraryCardPresentation(other: MusicPlaylist): Boolean =
    libraryCardPresentation() == other.libraryCardPresentation()

private fun MusicPlaylist.libraryCardPresentation() = QqLibraryCardPresentation(
    id = id,
    source = source,
    title = title,
    subtitle = subtitle,
    count = count,
    artworkStart = artworkStart,
    artworkEnd = artworkEnd,
    artworkMark = artworkMark,
    artworkUrl = artworkUrl,
)
