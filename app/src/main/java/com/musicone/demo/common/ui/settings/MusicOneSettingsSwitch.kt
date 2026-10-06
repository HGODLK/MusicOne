package com.musicone.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置页共用的双向动画开关，开启与关闭使用同一套过渡。 */
@Composable
internal fun MusicOneSettingsSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
        musicMotion(240),
        label = "设置开关轨道",
    )
    val thumbColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        musicMotion(240),
        label = "设置开关滑块",
    )
    val borderColor by animateColorAsState(
        MaterialTheme.colorScheme.outline.copy(alpha = if (checked) 0f else .35f),
        musicMotion(240),
        label = "设置开关边框",
    )
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 4.dp, musicMotion(240), label = "设置开关位移")
    Box(
        modifier.size(width = 52.dp, height = 32.dp)
            .background(trackColor, CircleShape)
            .border(1.dp, borderColor, CircleShape),
    ) {
        Box(Modifier.offset(x = thumbOffset, y = 4.dp).size(24.dp).background(thumbColor, CircleShape))
    }
}
