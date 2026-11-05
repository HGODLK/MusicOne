package com.musicone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState

@Composable
internal fun MainSourceHomeSongCard(card: MainSourceHomeCard.Song, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val track = card.track
    val artwork by rememberArtworkBitmap(track.artworkUrl, maxSide = LocalMainSourceFeedArtworkSizes.current.card)
    val selection by animateFloatAsState(if (current) 1f else 0f, musicMotion(240), label = "歌曲卡选中边框")
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = selection))) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CrossfadingArtworkBitmapOrPlaceholder(artwork, artwork == null && !track.artworkUrl.isNullOrBlank(),
                    track.artworkStart, track.artworkEnd, track.artworkMark, Modifier.fillMaxSize(), 48.sp, RectangleShape)
                MainSourceFeedPlayingIndicator(current, playing)
            }
            MainSourceHomeArtworkGradient(artwork, track.artworkUrl ?: track.id, Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp)) {
                    val textWidth = constraints.maxWidth
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MainSourceHomeText(track.title, textWidth, emphasis = true, preferredLines = 1, maxLines = 1, compact = true) {
                            OfflineTrackBadge(track, onDark = false)
                        }
                        MainSourceHomeText(track.artists, textWidth, preferredLines = 1, maxLines = 1, compact = true)
                        if (card.recommendationTitle.isNotBlank()) MainSourceHomeText(card.recommendationTitle, textWidth, compact = true)
                    }
                }
            }
        }
    }
}
