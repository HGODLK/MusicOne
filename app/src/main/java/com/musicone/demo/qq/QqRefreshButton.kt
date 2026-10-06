package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Chrome 风格的小圆底刷新箭头，触摸区域仍保持 48dp。 */
@Composable
internal fun QqRefreshButton(
    loading: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                rotation.animateTo(360f, tween(820, easing = LinearEasing))
                rotation.snapTo(0f)
            }
        } else if (rotation.value > .5f) {
            rotation.animateTo(360f, musicMotion(220))
            rotation.snapTo(0f)
        }
    }
    Box(
        modifier.size(48.dp).clip(CircleShape).semantics {
            this.contentDescription = contentDescription
            if (loading) stateDescription = "正在刷新"
        }.clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Refresh, null,
                Modifier.size(18.dp).graphicsLayer { rotationZ = rotation.value },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
