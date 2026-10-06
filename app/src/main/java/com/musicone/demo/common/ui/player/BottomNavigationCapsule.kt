package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

@Composable
internal fun BottomCapsule(
    page: MusicOnePage,
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    selectionPosition: () -> Float,
    onSelectionDrag: (Float) -> Unit,
    onPageChange: (MusicOnePage) -> Unit,
    onNavigationContact: () -> Unit,
    navigationDragBlocked: () -> Boolean,
    immersiveVisual: NeteaseProfileVisual? = null,
) {
    val primaryHeaderState = LocalPrimaryPageHeaderState.current
    val currentNavigationContact by rememberUpdatedState(onNavigationContact)
    val currentDragBlocked by rememberUpdatedState(navigationDragBlocked)
    val currentSelectionPosition by rememberUpdatedState(selectionPosition)
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val visualPosition = remember { { dragPosition ?: currentSelectionPosition() } }
    var selectionTravelPx by remember { mutableFloatStateOf(1f) }
    fun settleDrag() {
        val position = (dragPosition ?: selectionPosition())
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
        dragPosition = null
        onPageChange(rootPageForPosition(position))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .3f
        val neutral = LocalPlatformNeutral.current
        val activeColor = if (neutral) MaterialTheme.colorScheme.primary
            else immersiveVisual?.foreground ?: MaterialTheme.colorScheme.primary
        val inactiveColor = immersiveVisual?.secondary ?: MaterialTheme.colorScheme.onSurfaceVariant
        val selectedColor = navigationCapsuleSelectionColor(
            neutral, MaterialTheme.colorScheme.primaryContainer, immersiveVisual?.foreground,
        )
        MusicOneBackdropGlass(
            backdropLayer = backdropLayer,
            backdropBounds = backdropBounds,
            modifier = Modifier.width(246.dp),
            shape = RoundedCornerShape(28.dp),
            blurRadius = 20.dp,
            containerColor = immersiveVisual?.panelColor
                ?: if (dark) Color(0xFF242526).copy(alpha = .82f) else Color.White.copy(alpha = .24f),
            fallbackColor = immersiveVisual?.panelColor
                ?: if (dark) Color(0xFF242526) else Color(0xFFF3F4F6),
            border = androidx.compose.foundation.BorderStroke(
                .8.dp,
                immersiveVisual?.panelBorder ?: Color.White.copy(alpha = if (dark) .16f else .72f),
            ),
        ) {
            Box(
                Modifier.padding(5.dp).fillMaxWidth().height(52.dp)
                    .onSizeChanged { selectionTravelPx = (it.width / 2f).coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            currentNavigationContact()
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        }
                    }
                    .pointerInput(selectionTravelPx) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragPosition = if (currentDragBlocked()) null else selectionPosition()
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                nextCapsuleDragPosition(
                                    dragPosition,
                                    selectionPosition(),
                                    dragAmount,
                                    selectionTravelPx,
                                    currentDragBlocked(),
                                )?.let { updated ->
                                    dragPosition = updated
                                    onSelectionDrag(updated)
                                }
                            },
                            onDragEnd = { if (dragPosition != null) settleDrag() },
                            onDragCancel = { if (dragPosition != null) settleDrag() },
                        )
                    },
            ) {
                NavigationCapsuleSelection(
                    page = page,
                    position = visualPosition,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor,
                    selectionColor = selectedColor,
                    onHome = {
                        if (page == MusicOnePage.HOME && kotlin.math.abs(selectionPosition()) < .001f) {
                            primaryHeaderState.requestScrollToTop(MusicOnePage.HOME)
                        } else {
                            onPageChange(MusicOnePage.HOME)
                        }
                    },
                    onMy = { onPageChange(MusicOnePage.MY) },
                )
            }
        }
    }
}

internal fun nextCapsuleDragPosition(
    current: Float?,
    selection: Float,
    dragAmount: Float,
    travel: Float,
    blocked: Boolean,
): Float? = if (blocked) null else ((current ?: selection) + dragAmount / travel.coerceAtLeast(1f))
    .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
