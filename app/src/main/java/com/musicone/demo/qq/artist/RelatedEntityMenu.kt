package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*

@Composable
internal fun RelatedEntityMenu(
    menu: QqPlaylistSongMenuState,
    choosingArtist: Boolean = menu.choosingArtist,
    artworkVisible: Boolean = true,
) {
    val navigation = LocalEntityNavigation.current ?: return
    val track = menu.track ?: return
    if (choosingArtist) {
        SongMenuRow("返回", !menu.busy) { menu.back() }
        track.artistRefs.forEach { artist ->
            ArtistMenuRow(artist.name, artist.mid, artist.name, menu, !menu.busy, artworkVisible) {
                navigation.open(EntityTarget.Artist(QqSearchSinger(artist.mid, artist.name,
                    "https://y.gtimg.cn/music/photo_new/T001R500x500M000${artist.mid}.jpg")), menu, "menu:${menu.track?.id}:${artist.mid}")
            }
        }
    } else {
        ArtistMenuRow("查看歌手", track.artistRefs.singleOrNull()?.mid,
            track.artistRefs.singleOrNull()?.name.orEmpty(), menu,
            !menu.busy && track.artistRefs.isNotEmpty(), artworkVisible) {
            if (track.artistRefs.size == 1) {
                val artist = track.artistRefs.first()
                navigation.open(EntityTarget.Artist(QqSearchSinger(artist.mid, artist.name,
                    "https://y.gtimg.cn/music/photo_new/T001R500x500M000${artist.mid}.jpg")), menu, "menu:${menu.track?.id}:${artist.mid}")
            } else menu.choosingArtist = true
        }
        val album = track.relatedAlbum()
        AnimatedVisibility(album != null,
            enter = fadeIn(musicMotion(240)) + slideInVertically(musicMotion(280)) { it / 4 } + expandVertically(musicMotion(280)),
            exit = fadeOut(musicMotion(180)) + slideOutVertically(musicMotion(240)) { -it / 4 } + shrinkVertically(musicMotion(240))) {
            SongMenuRow("查看专辑", !menu.busy && album != null) {
                album?.let { navigation.open(EntityTarget.Album(it), menu) }
            }
        }
    }
}

@Composable
private fun ArtistMenuRow(
    label: String,
    mid: String?,
    artistName: String,
    menu: QqPlaylistSongMenuState,
    enabled: Boolean,
    artworkVisible: Boolean,
    click: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = click).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mid != null) MenuArtistArtwork(mid, "menu:${menu.track?.id}:$mid", artistName.take(1), artworkVisible)
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f), fontSize = 14.sp)
    }
}
