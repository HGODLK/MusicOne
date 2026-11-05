package com.musicone

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
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
    animateFirstArtwork: Boolean = false,
    onArtworkSnapshot: ((List<ArtworkBlendSnapshot>) -> Unit)? = null) {
    val cards = LocalPlaylistCardTransition.current
    val context = LocalContext.current.applicationContext
    val firstUrl = remember { imageUrl }
    var cached by remember { mutableStateOf<Bitmap?>(null) }
    var cacheReady by remember { mutableStateOf(bitmap != null || firstUrl.isNullOrBlank()) }
    LaunchedEffect(Unit) {
        try { if (!cacheReady) cached = ArtworkRepository.cached(firstUrl, context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 缓存不可用时沿用原占位和异步读取。 */ }
        finally { cacheReady = true }
    }
    val firstBitmap = bitmap ?: cached.takeIf { imageUrl == firstUrl }
    val candidate = PlayerArtworkFrame(ArtworkIdentity(imageUrl, start, end, mark), firstBitmap)
    val initial = remember { initialArtwork ?: cards?.takeIf { it.activeSourceKey == sourceKey }?.activeArtworkLayers
        ?: listOf(ArtworkBlendSnapshot(candidate, 1f)) }
    var prepared by remember { mutableStateOf(initial.last().frame) }
    var displayedBitmap by remember { mutableStateOf(initial.any { it.frame.bitmap != null }) }
    var showedPlaceholder by remember { mutableStateOf(false) }
    val motion = LocalPlaylistMotion.current
    SideEffect {
        // 占位已真实可见后到达的封面统一走混合淡入；详情页头部仍等页面展开完成后才算可见。
        if (cacheReady && firstBitmap == null && (!animateFirstArtwork || motion?.phase == MotionPhase.SHOWN)) {
            showedPlaceholder = true
        }
    }
    LaunchedEffect(candidate, awaitingArtwork) { if (!awaitingArtwork || firstBitmap != null) prepared = candidate }
    DisposableEffect(cards, sourceKey) { onDispose { cards?.artworkSync?.forget(sourceKey) } }
    // 磁盘命中先准备再显示；其后完全沿用原混合与交接，不改变任何运动参数。
    if (!cacheReady && bitmap == null && !displayedBitmap) { Box(modifier); return }
    val frozen = freezeArtwork || cards?.artworkLocked(sourceKey) == true
    val displayFrame = if (!frozen && !displayedBitmap && firstBitmap != null) candidate else prepared
    // 缓存首帧仍直接交接；页面已显示占位后才到达的图片复用原封面混合。
    val adoptFirstBitmap = !displayedBitmap && displayFrame.bitmap != null && !showedPlaceholder
    ReadyArtworkCrossfade(displayFrame, if (ExperiencePreferences.options.reduceMotion) 180 else 360,
        modifier, freezeBlend = frozen, initialBlend = if (adoptFirstBitmap)
            listOf(ArtworkBlendSnapshot(displayFrame, 1f)) else initial,
        respectReduceMotion = false, adoptPreparedFrame = !frozen && adoptFirstBitmap,
        onDisplayed = {
            if (it.bitmap != null) displayedBitmap = true
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
