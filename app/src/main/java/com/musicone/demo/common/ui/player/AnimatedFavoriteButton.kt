package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val PlayerFavoriteColor = Color(0xFFFF7C83)

@Composable
internal fun AnimatedFavoriteButton(
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scale = remember { Animatable(1f) }
    val halo = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    val haptic = LocalHapticFeedback.current
    val idleColor = LocalContentColor.current

    IconButton(
        enabled = enabled,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
            val initialVelocity = scale.velocity
            feedbackJob?.cancel()
            feedbackJob = scope.launch {
                coroutineScope {
                    launch {
                        scale.animateTo(.92f, musicMotion(180), initialVelocity = initialVelocity)
                        scale.animateTo(1f, musicMotion(150))
                    }
                    launch {
                        halo.snapTo(1f)
                        halo.animateTo(0f, musicMotion(380))
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = PlayerFavoriteColor.copy(alpha = halo.value * .34f),
                    radius = size.minDimension * (.28f + (1f - halo.value) * .2f),
                )
            }
            Icon(
                imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) PlayerFavoriteColor else idleColor,
                modifier = Modifier.size(27.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
    }
}
