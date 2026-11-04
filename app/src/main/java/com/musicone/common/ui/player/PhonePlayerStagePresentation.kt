package com.musicone

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 连续进度只派生开关状态；开合中间帧不再让整个舞台参与重组。 */
internal data class PhonePlayerStagePresentation(
    val lyricsActive: Boolean,
    val fullyOpen: Boolean,
    val intermediate: Boolean,
    val heldTrackChange: Boolean,
    val informationMenu: Boolean,
)

@Composable
internal fun rememberPhonePlayerStagePresentation(motion: PhoneLyricsMotion, lyrics: Boolean,
    playerVisible: Boolean, source: MusicSource): PhonePlayerStagePresentation {
    val state by remember(motion, lyrics, playerVisible, source) { derivedStateOf {
        val progress = motion.lyricsProgress
        val header = motion.headerProgress
        PhonePlayerStagePresentation(
            lyricsActive = playerVisible && (lyrics || progress > .0005f),
            fullyOpen = lyrics && progress == 1f && header == 1f && !motion.dragging,
            intermediate = !((progress == 0f && header == 0f) || (progress == 1f && header == 1f)),
            heldTrackChange = motion.dragging && motion.entranceProgress > 0f && motion.entranceProgress < 1f,
            informationMenu = phonePlayerInformationMenuEnabled(source, lyrics, progress, header, motion.dragging),
        )
    } }
    return state
}

/** 圆角沿用原来的 4dp 量化补偿，变化只通知封面，不拖动歌词与整页一起重组。 */
@Composable
internal fun PhoneStageArtwork(track: MusicTrack, playerMotion: PageMotion, lyricsMotion: PhoneLyricsMotion,
    modifier: Modifier) {
    val corner by remember(lyricsMotion) { derivedStateOf { phoneArtworkCorner(lyricsMotion.headerProgress) } }
    PhoneTurningArtwork(track, playerMotion, modifier, 110.sp, corner)
}

/** 圆角保存为实际显示像素比例，在两层缩放之后仍保持同一条圆弧。 */
internal class PhoneTurnArtworkShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height),
            CornerRadius((size.width * fraction).coerceIn(0f, minOf(size.width, size.height) / 2f))))
}

@Composable
internal fun PhoneTurningArtwork(track: MusicTrack, playerMotion: PageMotion, modifier: Modifier,
    markSize: TextUnit, defaultCornerDp: Float) {
    val turn = LocalPhonePlayerTurn.current
    val shape = remember(defaultCornerDp) { RoundedCornerShape(defaultCornerDp.dp) }
    // 连续转向比例只在封面裁剪图层读取，不能带动图片及交叉渐变子树逐帧重组。
    val shapeProvider: () -> Shape = remember(turn, shape) {
        {
            val fraction = turn?.currentPose("cover")?.let {
                it.corner / it.size.width.coerceAtLeast(.001f)
            } ?: 0f
            if (fraction > 0f) PhoneTurnArtworkShape(fraction) else shape
        }
    }
    PlayerArtwork(track, playerMotion, modifier, markSize, shape, shapeProvider)
}
