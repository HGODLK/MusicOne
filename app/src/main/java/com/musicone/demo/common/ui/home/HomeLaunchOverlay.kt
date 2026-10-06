package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.Saver

private const val HOME_ARTWORK_WAIT_LIMIT_MS = 5_000L

@Stable
internal class HomeLaunchState(covering: Boolean = true) {
    var covering by mutableStateOf(covering)
}

@Composable
internal fun rememberHomeLaunchState(): HomeLaunchState = rememberSaveable(
    saver = Saver(save = { it.covering }, restore = { HomeLaunchState(it) }),
) { HomeLaunchState() }

@Composable
internal fun HomeLaunchOverlay(
    source: MusicSource,
    sessionRevision: Long,
    sessionStatus: SessionStatus,
    catalog: MusicCatalogUiState,
    launch: HomeLaunchState,
    playerRetention: PlayerRetentionState,
) {
    // 窗口尺寸切换即使触发 Activity 重建，也不能被误判成一次新的冷启动。
    val covering = launch.covering
    val metadataReady = homeLaunchMetadataReady(source, sessionRevision, sessionStatus, catalog)
    val artworkUrls = remember(source, catalog.recommendations, catalog.recommendedTracks) {
        homeLaunchArtworkUrls(source, catalog)
    }
    LaunchedEffect(metadataReady, artworkUrls) {
        if (!covering || !metadataReady) return@LaunchedEffect
        val deadline = SystemClock.uptimeMillis() + HOME_ARTWORK_WAIT_LIMIT_MS
        while (
            artworkUrls.any { ArtworkRepository.peek(it) == null } &&
            SystemClock.uptimeMillis() < deadline
        ) {
            delay(50L)
        }
        // 只等本地预热的实际绘制；低内存直接放行，慢设备最多额外等待半秒。
        withTimeoutOrNull(500L) { snapshotFlow { playerRetention.launchReady }.first { it } }
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }

    AnimatedVisibility(
        visible = covering,
        exit = fadeOut(musicMotion(responseMillis = 420)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clearAndSetSemantics { stateDescription = "正在准备首页" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
    }
}

internal fun homeLaunchMetadataReady(
    source: MusicSource,
    sessionRevision: Long,
    sessionStatus: SessionStatus,
    catalog: MusicCatalogUiState,
): Boolean = catalog.source == source && catalog.sessionRevision == sessionRevision &&
    sessionStatus != SessionStatus.CHECKING &&
    catalog.recommendedTracksSettled && (source == MusicSource.QQ || catalog.recommendationsSettled)

internal fun homeLaunchArtworkUrls(source: MusicSource, catalog: MusicCatalogUiState): List<String> =
    when (source) {
        MusicSource.QQ -> catalog.recommendedTracks.firstOrNull()?.artworkUrl?.let(::listOf).orEmpty()
        MusicSource.NETEASE, MusicSource.KUGOU -> catalog.recommendations.take(2).mapNotNull { it.artworkUrl }
    }.map(String::trim).filter(String::isNotBlank).distinct()
