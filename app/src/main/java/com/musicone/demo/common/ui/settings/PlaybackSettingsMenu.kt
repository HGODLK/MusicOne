package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class PlaybackSettingsMenuOption(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val description: String? = null,
    val select: () -> Unit,
)

@Stable
internal class PlaybackSettingsMenuState {
    var expanded by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var triggerOrigin by mutableStateOf(Offset.Zero)
        private set
    var options by mutableStateOf(emptyList<PlaybackSettingsMenuOption>())
        private set

    fun open(title: String, triggerOrigin: Offset, options: List<PlaybackSettingsMenuOption>) {
        this.title = title
        this.triggerOrigin = triggerOrigin
        this.options = options
        expanded = true
    }

    fun dismiss() {
        expanded = false
    }

    fun select(option: PlaybackSettingsMenuOption) {
        if (!option.enabled) return
        option.select()
        dismiss()
    }
}

internal val LocalPlaybackSettingsMenu = staticCompositionLocalOf<PlaybackSettingsMenuState?> { null }

@Composable
internal fun PlaybackSettingsMenuButton(
    label: String,
    title: String,
    options: List<PlaybackSettingsMenuOption>,
    modifier: Modifier = Modifier,
) {
    val menu = LocalPlaybackSettingsMenu.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var pressOrigin by remember(title) { mutableStateOf<Offset?>(null) }
    TextButton(
        enabled = menu != null,
        onClick = { menu?.open(title, pressOrigin ?: bounds.center, options) },
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(title, menu) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressOrigin = bounds.topLeft + down.position
                }
            },
    ) {
        Text(label)
    }
}

/** 录制设置页作为采样源，菜单从实际按下位置展开并按原路径收回。 */
@Composable
internal fun PlaybackSettingsMenuHost(bottomInset: Dp = 0.dp, content: @Composable () -> Unit) {
    val state = remember { PlaybackSettingsMenuState() }
    val backdrop = rememberGraphicsLayer()
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()

    CompositionLocalProvider(LocalPlaybackSettingsMenu provides state) {
        BoxWithConstraints(
            Modifier.fillMaxSize().onGloballyPositioned { hostBounds = it.boundsInRoot() },
        ) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, state.expanded)) { content() }

            val left = safe.calculateStartPadding(direction) + 12.dp
            val right = safe.calculateEndPadding(direction) + 12.dp
            val top = safe.calculateTopPadding() + 12.dp
            val bottom = maxOf(bottomInset, safe.calculateBottomPadding()) + 12.dp
            val width = minOf(336.dp, (maxWidth - left - right).coerceAtLeast(1.dp))
            val maximumHeight = (maxHeight - top - bottom).coerceAtLeast(1.dp)
            val safeBounds = with(density) { Rect(left.toPx(), top.toPx(),
                (maxWidth - right).toPx(), (maxHeight - bottom).toPx()) }
            val transformOrigin = qualityMenuTransformOrigin(state.triggerOrigin, hostBounds)

            AnimatedVisibility(
                visible = state.expanded,
                enter = fadeIn(musicMotion(220)) + scaleIn(
                    musicMotion(280), initialScale = .84f, transformOrigin = transformOrigin,
                ),
                exit = fadeOut(musicMotion(180)) + scaleOut(
                    musicMotion(220), targetScale = .92f, transformOrigin = transformOrigin,
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::dismiss,
                    ),
                ) {
                    Layout(content = {
                    MusicOneBackdropGlass(
                        backdropLayer = backdrop,
                        backdropBounds = hostBounds,
                        modifier = Modifier.width(width)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                        shape = RoundedCornerShape(28.dp),
                        blurRadius = 24.dp,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .66f),
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)),
                    ) {
                        Column(
                            Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        ) {
                            Text(
                                state.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                            state.options.forEach { option -> PlaybackSettingsMenuRow(option) { state.select(option) } }
                        }
                    }
                    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                        val menuWidth = with(density) { width.roundToPx() }.coerceAtMost(constraints.maxWidth)
                        val menuHeight = with(density) { maximumHeight.roundToPx() }.coerceAtMost(constraints.maxHeight)
                        val menu = measurables.single().measure(constraints.copy(
                            minWidth = menuWidth, maxWidth = menuWidth, minHeight = 0, maxHeight = menuHeight))
                        val origin = state.triggerOrigin - hostBounds.topLeft
                        val position = settingsMenuOffset(origin, safeBounds, IntSize(menu.width, menu.height))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            menu.place(position.x.toInt(), position.y.toInt())
                        }
                    }
                }
            }
        }
    }
    if (state.expanded) BackHandler(onBack = state::dismiss)
}

@Composable
private fun PlaybackSettingsMenuRow(option: PlaybackSettingsMenuOption, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .selectable(option.selected, enabled = option.enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 15.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(end = 36.dp)) {
            val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (option.enabled) 1f else .38f)
            Text(option.label, fontSize = 14.sp, color = color)
            option.description?.let { Text(it, fontSize = 11.sp, color = color) }
        }
        if (option.selected) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
