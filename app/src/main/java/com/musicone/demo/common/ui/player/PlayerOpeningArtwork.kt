package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class ArtworkBlendSnapshot(
    val frame: PlayerArtworkFrame,
    val opacity: Float,
)

/** 保存迷你播放器当前可见展示状态与混合比例，供展开时直接接续同一份封面。 */
internal object PlayerOpeningArtworkHandoff {
    data class Entry(
        val source: MusicSource,
        val id: String,
        val identity: ArtworkIdentity,
        val frame: PlayerArtworkFrame,
        val blends: List<ArtworkBlendSnapshot>? = null,
    )

    var displayed by mutableStateOf<Entry?>(null)
        private set

    fun offer(track: MusicTrack, frame: PlayerArtworkFrame, blends: List<ArtworkBlendSnapshot>? = null) {
        val identity = track.artworkIdentity()
        displayed = Entry(track.source, track.id, identity, frame, blends)
    }

    fun handoffFor(track: MusicTrack): Entry? = displayed?.takeIf {
        it.source == track.source && it.id == track.id && it.identity == track.artworkIdentity()
    }

    fun frameFor(track: MusicTrack): PlayerArtworkFrame? = handoffFor(track)?.frame
}

/** 展开期间优先沿用迷你播放器已画出的封面，其余切歌仍走原有渐变。 */
@Composable
internal fun rememberPlayerOpeningArtwork(track: MusicTrack, motion: PageMotion): PlayerArtworkFrame {
    val loaded by rememberPlayerArtwork(track)
    val identity = track.artworkIdentity()
    val opening = motion.wantsOpen &&
        (motion.phase == MotionPhase.PREPARING || motion.phase == MotionPhase.MOVING)
    val offered = PlayerOpeningArtworkHandoff.frameFor(track)
    var retained by remember(track.source, track.id, identity) { mutableStateOf<PlayerArtworkFrame?>(null) }
    if (opening && offered != null) SideEffect { retained = offered }
    return when {
        opening && offered != null -> offered
        retained != null -> retained!!
        loaded.identity == identity && loaded.bitmap != null -> loaded
        else -> loaded
    }
}
