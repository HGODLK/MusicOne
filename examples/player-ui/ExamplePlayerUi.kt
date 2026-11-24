package org.example.source

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourcePlayerDismissGestureActions
import com.musicone.source.sdk.SourcePlayerForm
import com.musicone.source.sdk.SourcePlayerLyricsStyle
import com.musicone.source.sdk.SourcePlayerOpening
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerUiContext
import com.musicone.source.sdk.SourcePlayerUiDeclaration
import com.musicone.source.sdk.SourcePlayerUiProvider
import com.musicone.source.sdk.dismissGestureOrNull
import com.musicone.source.sdk.lyricsMotionOrNull

class ExamplePlayerUi : SourcePlayerUiProvider {
    override val playerUiDeclaration = SourcePlayerUiDeclaration(
        supportsPhone = true,
        supportsTablet = true,
        supportsPhoneLandscape = true,
        opening = SourcePlayerOpening(
            durationMillis = 420,
            barParticipates = true,
            sharedElements = setOf(
                SourcePlayerSharedElement.TITLE,
                SourcePlayerSharedElement.ARTIST,
                SourcePlayerSharedElement.PLAY_PAUSE,
            ),
            // 从迷你条展开，收起时共享元素飞回；背景开头就不透明。
            pageFade = false,
            sharedOnClose = true,
        ),
    )

    @Composable
    override fun Player(context: SourcePlayerUiContext) {
        val insets = context.safeDrawingInsets
        // 宿主封面色场的第一格作背景；未取到色时用默认色。
        val background = context.snapshot.artworkColors?.firstOrNull()?.let(::Color) ?: Color(0xFF202020)
        val modifier = Modifier.fillMaxSize()
            .absolutePadding(insets.left, insets.top, insets.right, insets.bottom)
            .padding(24.dp)
        Box(Modifier.fillMaxSize().background(background).dismissDrag(context)) {
            if (context.form == SourcePlayerForm.PHONE_LANDSCAPE) {
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { Controls(context) }
                    Lyrics(context, SourcePlayerLyricsStyle.SIDE, Modifier.weight(1f))
                }
            } else {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Controls(context)
                    Lyrics(context, SourcePlayerLyricsStyle.STACKED, Modifier.weight(1f))
                }
            }
        }
    }

    /** 新宿主用开合面板，旧宿主退回静态歌词。 */
    @Composable
    private fun Lyrics(context: SourcePlayerUiContext, style: SourcePlayerLyricsStyle, modifier: Modifier) {
        val lyrics = context.lyricsMotionOrNull()
        if (lyrics == null) { context.Lyrics(modifier); return }
        Button(enabled = context.transition.interactive, onClick = { lyrics.setOpened(!lyrics.opened) }) {
            Text(if (lyrics.opened) "收起歌词" else "歌词")
        }
        lyrics.LyricsPanel(style, modifier)
    }

    @Composable
    private fun Controls(context: SourcePlayerUiContext) {
        val state = context.snapshot
        // 共享目标需要非零布局；缺失文字也显示真实占位，避免开页准备超时。
        val title = state.track?.track?.title?.takeIf { it.isNotBlank() }
            ?: if (state.track == null) "暂无歌曲" else "未知歌曲"
        val artist = state.track?.artistsText?.takeIf { it.isNotBlank() } ?: "未知歌手"
        context.SharedElement(SourcePlayerSharedElement.TITLE) {
            Text(title)
        }
        context.SharedElement(SourcePlayerSharedElement.ARTIST) {
            Text(artist)
        }
        context.SharedElement(SourcePlayerSharedElement.PLAY_PAUSE) {
            Button(enabled = context.transition.interactive, onClick = { context.actions.togglePlay() }) {
                Text(if (state.isPlaying) "暂停" else "播放")
            }
        }
        // 非共享区域也使用宿主进度，不另建开页计时器；收起时进度从 1 回到 0。
        Column(Modifier.graphicsLayer {
            alpha = context.transition.progress.coerceIn(0f, 1f)
        }) {
            Row {
                Button(enabled = context.transition.interactive, onClick = { context.actions.previous() }) { Text("上一首") }
                Button(enabled = context.transition.interactive, onClick = { context.actions.next() }) { Text("下一首") }
                Button(enabled = context.transition.interactive, onClick = { context.actions.openQueue() }) { Text("队列") }
            }
            state.qualities.forEach { quality ->
                Button(
                    enabled = context.transition.interactive && !state.qualityChanging,
                    onClick = { context.actions.selectQuality(quality.id) },
                ) { Text(quality.label) }
            }
            state.errorMessage?.let { Text(it) }
            Button(onClick = context::dismiss) { Text("收起") }
        }
    }

    /** 下拉收起：一轮手势持有同一个动作对象，以 End 或 Cancel 结束；旧宿主取不到时不挂手势。 */
    private fun Modifier.dismissDrag(context: SourcePlayerUiContext): Modifier = pointerInput(context) {
        var gesture: SourcePlayerDismissGestureActions? = null
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                gesture = context.actions.dismissGestureOrNull()?.takeIf { it.dismissDragStart() }
            },
            onVerticalDrag = { change, delta ->
                val current = gesture ?: return@detectVerticalDragGestures
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                current.dismissDrag(delta)
            },
            onDragEnd = { gesture?.dismissDragEnd(tracker.calculateVelocity().y); gesture = null },
            onDragCancel = { gesture?.dismissDragCancel(); gesture = null },
        )
    }
}
