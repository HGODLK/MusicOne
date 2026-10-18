package com.musicone.demo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit

/** 保留当前混合画面，源卡片和飞行层使用同一份显示快照。 */
@Composable
internal fun PlaylistSyncedArtwork(bitmap: Bitmap?, awaitingArtwork: Boolean, start: Long, end: Long,
    mark: String, modifier: Modifier, markSize: TextUnit, shape: Shape,
    markAlignment: Alignment = Alignment.TopEnd, sourceKey: String, imageUrl: String? = null,
    onDisplayedArtworkChange: ((Bitmap?) -> Unit)? = null,
    initialArtwork: List<ArtworkBlendSnapshot>? = null,
    freezeArtwork: Boolean = false,
    onArtworkSnapshot: ((List<ArtworkBlendSnapshot>) -> Unit)? = null) {
    val cards = LocalPlaylistCardTransition.current
    val candidate = PlayerArtworkFrame(ArtworkIdentity(imageUrl, start, end, mark), bitmap)
    val initial = remember { initialArtwork ?: cards?.takeIf { it.activeSourceKey == sourceKey }?.activeArtworkLayers
        ?: listOf(ArtworkBlendSnapshot(candidate, 1f)) }
    var prepared by remember { mutableStateOf(initial.last().frame) }
    LaunchedEffect(candidate, awaitingArtwork) { if (!awaitingArtwork) prepared = candidate }
    DisposableEffect(cards, sourceKey) { onDispose { cards?.artworkSync?.forget(sourceKey) } }
    ReadyArtworkCrossfade(prepared, if (ExperiencePreferences.options.reduceMotion) 180 else 360,
        modifier, freezeBlend = freezeArtwork || cards?.artworkLocked(sourceKey) == true, initialBlend = initial,
        respectReduceMotion = false,
        onDisplayed = {
            val snapshot = listOf(ArtworkBlendSnapshot(it, 1f))
            cards?.artworkSync?.record(sourceKey, snapshot)
            onArtworkSnapshot?.invoke(snapshot)
            onDisplayedArtworkChange?.invoke(it.bitmap)
        },
        onBlendSnapshot = {
            cards?.artworkSync?.record(sourceKey, it)
            onArtworkSnapshot?.invoke(it)
        }) { frame ->
        ArtworkBitmapOrPlaceholder(frame.bitmap, frame.identity.start, frame.identity.end, frame.identity.mark,
            Modifier.fillMaxSize(), markSize, shape, markAlignment)
    }
}
