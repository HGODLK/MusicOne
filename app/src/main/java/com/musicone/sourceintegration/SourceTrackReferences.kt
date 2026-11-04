package com.musicone.sourceintegration

import com.musicone.MusicArtist
import com.musicone.MusicTrack
import com.musicone.source.sdk.SourceEntityRef

/** 旧曲目仍可用公开身份打开详情；新曲目保留音源提供的完整定位资料。 */
internal fun MusicArtist.sourceReference(): SourceEntityRef =
    reference?.takeIf { it.id == id } ?: SourceEntityRef(id, name, "artist")

internal fun MusicTrack.sourceAlbumReference(): SourceEntityRef? =
    albumReference?.takeIf { it.id == albumId }
        ?: albumId.takeIf(String::isNotBlank)?.let { SourceEntityRef(it, album, "album") }
