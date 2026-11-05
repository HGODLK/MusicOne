package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

/** 输入区置于聊天采样层之外，复用搜索及播放控件的实时玻璃。 */
@Composable
internal fun AiChatComposer(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit,
    backdrop: GraphicsLayer, bounds: Rect, modifier: Modifier = Modifier) {
    val occlusion = LocalAiComposerBackdrop.current
    DisposableEffect(occlusion) { onDispose { occlusion?.bounds = Rect.Zero } }
    var input by rememberSaveable(state.currentConversationId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.input, TextRange(state.input.length)))
    }
    val echoes = remember(state.currentConversationId) { AiInputEchoes() }
    LaunchedEffect(state.input) {
        // 同文回填保留输入法选区与组合态；仅外部真正换文时替换。
        val echo = echoes.consume(state.input)
        if (!echo && state.input != input.text) input = TextFieldValue(state.input, TextRange(state.input.length))
    }
    val motion = remember { MainSourceSearchMotion() }
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        if (ExperiencePreferences.options.reduceMotion) motion.menu.snapTo(1f)
        else motion.menu.animateTo(1f, PlayerEnterAnimation)
    }
    BoxWithConstraints(modifier.fillMaxWidth().onGloballyPositioned { occlusion?.bounds = it.boundsInRoot() }) {
    val viewport = maxWidth
    val lightSurface = MaterialTheme.colorScheme.background.luminance() >= .3f
    MusicOneBackdropGlass(backdrop, bounds, Modifier.fillMaxWidth(), RoundedCornerShape(28.dp),
        blurRadius = 28.dp,
        containerColor = if (lightSurface) Color.White.copy(alpha = .28f) else Color(0xFF242526).copy(alpha = .82f),
        fallbackColor = if (lightSurface) Color(0xFFF6F7F8) else Color(0xFF242526),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (lightSurface) Color(0xFFE0E2E6) else Color.White.copy(alpha = .16f)), visualOffset = {
            // 沿用搜索输入的开合进度、曲线与从右侧进入的定位函数，只移动合成后的玻璃。
            val open = motion.menu.value
            val frame = MainSourceSearchGeometry(viewport * open, 0.dp, 0.dp, 0.dp, 28.dp, viewport, 0.dp)
            Offset(with(density) { searchContentOffset(viewport, frame, open).toPx() }, 0f)
        }) {
        // 尺寸动画放在玻璃内部，让外层玻璃和列表留白读取同一个动画中高度。
        Column(Modifier.animateContentSize(animationSpec = musicMotion(220))
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            BasicTextField(input, {
                val textChanged = input.text != it.text
                input = it
                if (textChanged) { echoes.sent(it.text); onAction(AiUiAction.Input(it.text)) }
            },
                Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 160.dp)
                    .padding(end = 12.dp).semantics { contentDescription = "发消息" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
                enabled = !motion.menu.isRunning && motion.menu.value == 1f,
                decorationBox = { field -> Box {
                    if (input.text.isEmpty()) Text("发消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { onAction(if (state.running) AiUiAction.Stop else AiUiAction.Send) },
                    enabled = motion.menu.value == 1f && (state.running || state.input.isNotBlank()), modifier = Modifier.size(48.dp)) {
                    Icon(if (state.running) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                        contentDescription = if (state.running) "停止生成" else "发送消息")
                }
            }
        }
    }
    }
}
