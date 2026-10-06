package com.musicone.demo

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlayerCoverStage(track: MusicTrack, playing: Boolean, motion: PageMotion, modifier: Modifier, anchorsEnabled: Boolean = true) {
    val scale by rememberPlayerCoverScale(playing, motion)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, (maxHeight - 24.dp).coerceAtLeast(1.dp), 440.dp)
        val markSize = minOf(120f, side.value * .34f)
        Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }) {
            PlayerArtwork(track,
                Modifier.fillMaxSize().motionAnchor(if (anchorsEnabled) motion else null, "cover", true, corner = 12f * scale, markSize = markSize * scale, markX = 1f, markY = -1f, boundsScale = scale, contentScale = scale),
                markSize.sp, RoundedCornerShape(12.dp))
        }
    }
}

@Composable
internal fun PlayerSongInformation(track: MusicTrack, favorite: Boolean, motion: PageMotion,
    modifier: Modifier = Modifier,
    anchorsEnabled: Boolean = true, actionTrack: MusicTrack = track, onFavorite: () -> Unit) {
    val textMarquee = playerTextMarqueeEnabled(motion.phase, motion.targetContentHandoff)
    val ink = LocalContentColor.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).playerRelatedInformation(actionTrack, true)) {
            TrackTitle(track,
                modifier = Modifier
                    .motionAnchor(if (anchorsEnabled) motion else null, "title", true, hideDuringMotion = false, textSizeSp = 22f)
                    .playerTextHandoffCapture(motion, "title"),
                style = MusicOneTextStyles.playerTitle, color = ink,
                marquee = true, marqueeRunning = textMarquee,
                badgeOnDark = ink.luminance() > .5f, showBadges = false, primaryTitleOnly = true)
            Text(musicOneUiAnnotatedString(playerPrimaryArtists(track.artists)), style = MusicOneTextStyles.playerArtist,
                color = ink.copy(alpha = .62f), maxLines = 1,
                modifier = Modifier
                    .motionAnchor(if (anchorsEnabled) motion else null, "subtitle", true, hideDuringMotion = false, textSizeSp = 16f)
                    .playerTextHandoffCapture(motion, "subtitle")
                    .basicMarquee(iterations = if (textMarquee) Int.MAX_VALUE else 0))
        }
        AnimatedFavoriteButton(favorite, onFavorite, Modifier.playerDetailReveal(motion))
    }
}
