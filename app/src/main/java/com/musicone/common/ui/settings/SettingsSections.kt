package com.musicone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 卡片内设置行的统一左右内边距；卡片自身再留 4dp，文字距卡片边缘共 16dp。 */
internal val SettingsRowInset = 12.dp
private val SettingsCardInset = 4.dp
private val SettingsTitleInset = SettingsRowInset + SettingsCardInset

/** 一级分组：加粗大标题，下面依次是若干二级分组卡片。 */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = SettingsCardInset))
        content()
    }
}

/** 二级分组：卡片上方一行主音源色小标题，组内设置项放在同一张卡片里。 */
@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = SettingsTitleInset, end = SettingsTitleInset, bottom = 8.dp))
        SettingsCard(content)
    }
}

/** 圆角卡片，子项之间自动画细分隔线；高度为零的子项（未显示的设置）不参与分隔。 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val divider = MaterialTheme.colorScheme.outline.copy(alpha = .45f)
    var dividers by remember { mutableStateOf(emptyList<Float>()) }
    Layout(content, Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = SettingsCardInset, vertical = 4.dp)
        .drawWithContent {
            drawContent()
            val inset = SettingsRowInset.toPx()
            val stroke = .7.dp.toPx().coerceAtLeast(1f)
            dividers.forEach { y -> drawLine(divider, Offset(inset, y), Offset(size.width - inset, y), stroke) }
        }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        layout(width, placeables.sumOf { it.height }) {
            var y = 0
            var hasRowAbove = false
            val lines = ArrayList<Float>()
            placeables.forEach { placeable ->
                if (placeable.height > 0) {
                    if (hasRowAbove) lines += y.toFloat()
                    hasRowAbove = true
                }
                placeable.place(0, y)
                y += placeable.height
            }
            if (lines != dividers) dividers = lines
        }
    }
}

/** 统一的开关设置行：标题、说明与开关，整行可点。 */
@Composable
internal fun SettingsSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = SettingsRowInset, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MusicOneSettingsSwitch(checked)
    }
}

/**
 * 选项设置行：左侧标题（可带说明），右侧主音源色当前值与灰色“›”，点按从按下位置展开设置菜单。
 * 与开关行同高同边距，取代设置页里的绿色文字按钮。
 */
@Composable
internal fun SettingsMenuRow(title: String, value: String, options: List<PlaybackSettingsMenuOption>,
    subtitle: String? = null, menuTitle: String = title) {
    val menu = LocalPlaybackSettingsMenu.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var pressOrigin by remember(menuTitle) { mutableStateOf<Offset?>(null) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).onGloballyPositioned { bounds = it.boundsInRoot() }
        .pointerInput(menuTitle, menu) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressOrigin = bounds.topLeft + down.position
            }
        }
        .clickable(enabled = menu != null, role = Role.Button) { menu?.open(menuTitle, pressOrigin ?: bounds.center, options) }
        .padding(horizontal = SettingsRowInset, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 168.dp))
        Text(" ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 带右侧状态的入口行（主音源、版本更新），与其他设置项同一内边距。 */
@Composable
internal fun SettingsEntryRow(title: String, subtitle: String, trailing: @Composable () -> Unit,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = SettingsRowInset, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        trailing()
    }
}
