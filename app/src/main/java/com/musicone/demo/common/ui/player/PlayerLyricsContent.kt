package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CompletableDeferred

/** 单双栏迁移同一歌词子树，保留列表、逐字时钟和正在进行的交接。 */
internal class PlayerLyricsContent {
    val content = movableContentOf<PlayerLyricsRequest> { request ->
        PlayerSyncedLyricsContent(
            track = request.track,
            loadState = request.loadState,
            viewModel = request.viewModel,
            modifier = request.modifier,
            active = request.active,
            followPlayback = request.followPlayback,
            animateTrackChange = request.animateTrackChange,
            exitAlignment = request.exitAlignment,
            openingAlignment = request.openingAlignment,
            prepareWhileHidden = request.prepareWhileHidden,
            revealTopLineBeforeEntrance = request.revealTopLineBeforeEntrance,
            limitTransitionHistory = request.limitTransitionHistory,
            residency = request.residency,
            topExtensionPx = request.topExtensionPx,
            topBufferPx = request.topBufferPx,
            exitProgress = request.exitProgress,
            entranceProgress = request.entranceProgress,
            heldTrackChange = request.heldTrackChange,
            intermediateSeek = request.intermediateSeek,
            lyricsGestureHeld = request.lyricsGestureHeld,
            closingLyricsDrag = request.closingLyricsDrag,
            readingBounds = request.readingBounds,
            boundaryWindow = request.boundaryWindow,
            trackTransitionDirection = request.trackTransitionDirection,
            onWindowLayout = request.onWindowLayout,
        )
    }
}

internal val LocalPlayerLyricsContent = staticCompositionLocalOf<PlayerLyricsContent?> { null }
internal val LocalRetainedPhoneLyricsMotion = staticCompositionLocalOf<PhoneLyricsMotion?> { null }

internal data class PlayerLyricsRequest(
    val track: MusicTrack,
    val loadState: LyricLoadState,
    val viewModel: MusicPlaybackRuntime,
    val modifier: Modifier,
    val active: Boolean,
    val followPlayback: Boolean,
    val animateTrackChange: Boolean,
    val exitAlignment: CompletableDeferred<Unit>?,
    val openingAlignment: CompletableDeferred<Unit>?,
    val prepareWhileHidden: Boolean,
    val revealTopLineBeforeEntrance: Boolean,
    val limitTransitionHistory: Boolean,
    val residency: LyricResidency,
    val topExtensionPx: Int,
    val topBufferPx: Int,
    val exitProgress: () -> Float,
    val entranceProgress: (() -> Float)?,
    val heldTrackChange: Boolean,
    val intermediateSeek: Boolean,
    val lyricsGestureHeld: Boolean,
    val closingLyricsDrag: Boolean,
    val readingBounds: LyricReadingBounds?,
    val boundaryWindow: LyricBoundaryWindow?,
    val trackTransitionDirection: TrackTransitionDirection,
    val onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)?,
)

@Composable
internal fun PlayerSyncedLyrics(
    track: MusicTrack,
    loadState: LyricLoadState,
    viewModel: MusicPlaybackRuntime,
    modifier: Modifier,
    active: Boolean = true,
    followPlayback: Boolean = active,
    animateTrackChange: Boolean = followPlayback,
    exitAlignment: CompletableDeferred<Unit>? = null,
    openingAlignment: CompletableDeferred<Unit>? = null,
    prepareWhileHidden: Boolean = false,
    revealTopLineBeforeEntrance: Boolean = false,
    limitTransitionHistory: Boolean = false,
    residency: LyricResidency = LyricResidency.None,
    topExtensionPx: Int = 0,
    topBufferPx: Int = 0,
    exitProgress: () -> Float = { 1f },
    entranceProgress: (() -> Float)? = null,
    heldTrackChange: Boolean = false,
    intermediateSeek: Boolean = false,
    lyricsGestureHeld: Boolean = false,
    closingLyricsDrag: Boolean = false,
    readingBounds: LyricReadingBounds? = null,
    boundaryWindow: LyricBoundaryWindow? = null,
    trackTransitionDirection: TrackTransitionDirection = TrackTransitionDirection.NEXT,
    onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)? = null,
) {
    val retained = LocalPlayerLyricsContent.current
    val local = remember { PlayerLyricsContent() }
    (retained ?: local).content(PlayerLyricsRequest(
        track = track,
        loadState = loadState,
        viewModel = viewModel,
        modifier = modifier,
        active = active,
        followPlayback = followPlayback,
        animateTrackChange = animateTrackChange,
        exitAlignment = exitAlignment,
        openingAlignment = openingAlignment,
        prepareWhileHidden = prepareWhileHidden,
        revealTopLineBeforeEntrance = revealTopLineBeforeEntrance,
        limitTransitionHistory = limitTransitionHistory,
        residency = residency,
        topExtensionPx = topExtensionPx,
        topBufferPx = topBufferPx,
        exitProgress = exitProgress,
        entranceProgress = entranceProgress,
        heldTrackChange = heldTrackChange,
        intermediateSeek = intermediateSeek,
        lyricsGestureHeld = lyricsGestureHeld,
        closingLyricsDrag = closingLyricsDrag,
        readingBounds = readingBounds,
        boundaryWindow = boundaryWindow,
        trackTransitionDirection = trackTransitionDirection,
        onWindowLayout = onWindowLayout,
    ))
}
