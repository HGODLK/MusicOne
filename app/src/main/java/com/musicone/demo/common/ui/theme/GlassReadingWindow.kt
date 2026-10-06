package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun GlassReadingWindow(open: Boolean, title: String, text: String, onClose: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, musicMotion(if (open) 320 else 240)) }
    if (!open && progress.value == 0f) return
    val frame = LocalEntityFrame.current
    val pageBackdrop = frame?.pageLayer?.takeIf {
        frame.motion.phase == MotionPhase.SHOWN && it.size.width > 0 && it.size.height > 0
    }
    val backdrop = pageBackdrop ?: LocalPlayerBackdropTexture.current
    val bounds = if (pageBackdrop != null) frame.motion.hostBounds
        else LocalPlayerBackdropBounds.current
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, null) { onClose() },
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            val wide = maxWidth >= 600.dp
            val horizontalPadding = if (wide) 32.dp else 16.dp
            val windowWidth = if (wide) 760.dp else 560.dp
            val windowHeight = maxHeight * (if (wide) .76f else .70f)
            MusicOneBackdropGlass(backdrop, bounds,
                Modifier.padding(horizontal = horizontalPadding).widthIn(max = windowWidth).fillMaxWidth()
                    .heightIn(min = minOf(320.dp, maxHeight * .42f), max = windowHeight)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .graphicsLayer {
                alpha = progress.value; translationY = 24.dp.toPx() * (1f - progress.value)
            }, RoundedCornerShape(28.dp), blurRadius = 24.dp,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .8f), fallbackColor = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(text, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("收起", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}
