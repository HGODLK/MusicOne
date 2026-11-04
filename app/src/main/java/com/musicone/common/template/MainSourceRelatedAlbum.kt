package com.musicone

import com.musicone.sourceintegration.sourceAlbumReference

internal fun MusicTrack.relatedAlbum(): MusicPlaylist? = sourceAlbumReference()?.let { reference ->
    MusicPlaylist("${source.name.lowercase()}-album-${reference.id}", source, album, artists, "", 0,
        artworkStart, artworkEnd, artworkMark, emptyList(), artworkUrl,
        providerData = mapOf("templateEntityId" to reference.id, "templateEntityKind" to reference.kind,
            "templateEntityPayloadFormat" to reference.payload.format,
            "templateEntityPayloadVersion" to reference.payload.version.toString(),
            "templateEntityPayload" to reference.payload.encoded))
}
