package com.musicone.demo

internal fun MusicOneUiState.withLibraryMetadata(tracks: List<MusicTrack>): MusicOneUiState {
    val saved = tracks.associateBy { it.id }
    fun MusicTrack.updated(): MusicTrack = saved[id]?.let { latest -> copy(
        title = latest.title, artists = latest.artists, album = latest.album, artworkUrl = latest.artworkUrl,
        artworkStart = latest.artworkStart, artworkEnd = latest.artworkEnd, artworkMark = latest.artworkMark,
        localUri = latest.localUri,
        playable = latest.playable, unavailableReason = latest.unavailableReason,
    ) } ?: this
    return copy(currentTrack = currentTrack?.updated(), queue = queue.map { it.updated() })
}
