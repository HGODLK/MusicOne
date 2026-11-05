package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musicone.ExperiencePreferences
import com.musicone.musicMotion
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * AI 聊天空白欢迎页：
 * 预设灵感气泡直接长成用户消息切角样式。
 * 测量精准挂载在 Surface 实体上，Animatable 帧同步驱动精确二维斜向平移，
 * 标题与其余气泡纯原地淡出，动画跑满最后一帧平稳停在右上角时无缝交接真实消息。
 */
@Composable
internal fun AiChatWelcomeContent(
    webEnabled: Boolean,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val heading = remember { aiWelcomeHeading(Calendar.getInstance()) }
    var suggestions by remember { mutableStateOf(aiWelcomeSuggestions(Calendar.getInstance(), webEnabled)) }

    val coroutineScope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var animating by remember { mutableStateOf(false) }

    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bubblePositions = remember { mutableStateMapOf<Int, Offset>() }
    val bubbleSizes = remember { mutableStateMapOf<Int, IntSize>() }

    var deltaX by remember { mutableFloatStateOf(0f) }
    var deltaY by remember { mutableFloatStateOf(0f) }

    // 联网关闭后把依赖联网的气泡原位换掉；动画进行中不换，避免选中的气泡被替换
    LaunchedEffect(webEnabled) {
        if (!webEnabled && !animating) suggestions = aiWelcomeReplaceWebSuggestions(suggestions, Calendar.getInstance())
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerCoordinates = it }
    ) {
        val bubbleMaximumWidth = aiUserBubbleMaxWidth(maxWidth)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 大标题与副标题区：纯原地透明度淡出，尺寸不动，完全不产生抖动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .graphicsLayer {
                        alpha = if (animating) (1f - progress.value).coerceIn(0f, 1f) else 1f
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    heading.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    heading.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "试试这样问我：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 4 个灵感气泡：精准测量 Surface 实体自身尺寸与位置
            suggestions.forEachIndexed { index, suggestion ->
                val text = suggestion.text
                val isSelected = selectedIndex == index

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    AiUserBubble(
                        text = text,
                        maximumWidth = bubbleMaximumWidth,
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val root = containerCoordinates
                                if (root != null && root.isAttached && coords.isAttached) {
                                    bubblePositions[index] = root.localPositionOf(coords, Offset.Zero)
                                    bubbleSizes[index] = coords.size
                                }
                            }
                            .graphicsLayer {
                                if (animating) {
                                    if (isSelected) {
                                        translationX = progress.value * deltaX
                                        translationY = progress.value * deltaY
                                        alpha = 1f
                                    } else {
                                        alpha = (1f - progress.value).coerceIn(0f, 1f)
                                    }
                                } else {
                                    alpha = 1f
                                }
                            }
                            .clickable(enabled = !animating) {
                                val root = containerCoordinates
                                val pos = bubblePositions[index]
                                val size = bubbleSizes[index]
                                if (root != null && pos != null && size != null) {
                                    val targetX = (root.size.width - size.width).toFloat()
                                    deltaX = targetX - pos.x
                                    deltaY = -pos.y
                                }
                                selectedIndex = index
                                animating = true
                                coroutineScope.launch {
                                    progress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = if (ExperiencePreferences.options.reduceMotion) snap() else musicMotion(320)
                                    )
                                    // 必须实打实到达右上角落点后才交付消息
                                    onSelectPrompt(text)
                                }
                            }
                    )
                }
            }
        }
    }
}
