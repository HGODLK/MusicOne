package org.example.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourcePlayerForm
import com.musicone.source.sdk.SourcePlayerOpening
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerUiContext
import com.musicone.source.sdk.SourcePlayerUiDeclaration
import com.musicone.source.sdk.SourcePlayerUiProvider

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
        ),
    )

    @Composable
    override fun Player(context: SourcePlayerUiContext) {
        val insets = context.safeDrawingInsets
        val modifier = Modifier.fillMaxSize()
            .absolutePadding(insets.left, insets.top, insets.right, insets.bottom)
            .padding(24.dp)
        if (context.form == SourcePlayerForm.PHONE_LANDSCAPE) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { Controls(context) }
                context.Lyrics(Modifier.weight(1f))
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Controls(context)
                context.Lyrics(Modifier.weight(1f))
            }
        }
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
        // 非共享区域也使用宿主进度，不另建开页计时器。
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
}
