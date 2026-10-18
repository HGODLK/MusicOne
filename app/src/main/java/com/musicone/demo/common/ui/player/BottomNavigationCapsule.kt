package com.musicone.demo

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    queueState: PlayerQueueState,
    immersiveVisual: NeteaseProfileVisual? = null,
) {
    val primaryHeaderState = LocalPrimaryPageHeaderState.current
    val currentNavigationContact by rememberUpdatedState(onNavigationContact)
    val currentDragBlocked by rememberUpdatedState(navigationDragBlocked)
    val currentSelectionPosition by rememberUpdatedState(selectionPosition)
    val scope = rememberCoroutineScope()
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val visualPosition = remember { { dragPosition ?: currentSelectionPosition() } }
    var selectionTravelPx by remember { mutableFloatStateOf(1f) }
    fun settleDrag() {
        val position = (dragPosition ?: selectionPosition())
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
        val targetPage = rootPageForPosition(position)
        onPageChange(targetPage)
        scope.launch {
            // 保留拖动终点一帧，等承接动画建立起步状态后再撤下 dragPosition，避免短暂闪跳旧选择位置。
            androidx.compose.runtime.withFrameNanos { }
            dragPosition = null
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .3f
        val neutral = LocalPlatformNeutral.current
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = immersiveVisual?.secondary ?: MaterialTheme.colorScheme.onSurfaceVariant
        val selectedColor = navigationCapsuleSelectionColor(
            neutral, MaterialTheme.colorScheme.primaryContainer,
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
                    .navigationCapsuleGesture(
                        onContact = { currentNavigationContact() },
                        blocked = { currentDragBlocked() },
                        onHorizontalDrag = { amount ->
                            nextCapsuleDragPosition(dragPosition, currentSelectionPosition(), amount,
                                selectionTravelPx, currentDragBlocked())?.let { updated ->
                                dragPosition = updated
                                onSelectionDrag(updated)
                            }
                        },
                        onHorizontalEnd = { if (dragPosition != null) settleDrag() },
                        queueState = queueState,
                    ),
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
