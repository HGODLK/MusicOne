package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun navigationCapsuleSelectionBounds(width: Float, height: Float, position: Float): Rect {
    val itemWidth = width / MusicOnePage.entries.size
    val left = itemWidth * position.coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
    return Rect(left, 0f, left + itemWidth, height)
}

internal fun navigationCapsuleSelectionColor(neutral: Boolean, container: Color, immersive: Color?): Color = when {
    neutral -> container
    immersive != null -> immersive.copy(alpha = .24f)
    else -> container.copy(alpha = .56f)
}

/** 两层图标共享同一条圆角裁切边界；进度只在绘制时读取，点击和拖动都不会整枚跳色。 */
@Composable
internal fun NavigationCapsuleSelection(
    page: MusicOnePage,
    position: () -> Float,
    activeColor: Color,
    inactiveColor: Color,
    selectionColor: Color,
    onHome: () -> Unit,
    onMy: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        NavigationCapsuleLabels(
            inactiveColor, false,
            Modifier.matchParentSize().selectionMask(position, active = false),
        )
        NavigationCapsuleLabels(
            activeColor, true,
            Modifier.matchParentSize().selectionMask(position, active = true, selectionColor),
        )
        // 点击区域只保留一份语义，装饰层不重复朗读，命中范围不受颜色裁切影响。
        Row(Modifier.matchParentSize()) {
            NavigationCapsuleTarget("首页", page == MusicOnePage.HOME, Modifier.weight(1f), onHome)
            NavigationCapsuleTarget("我的", page == MusicOnePage.MY, Modifier.weight(1f), onMy)
        }
    }
}

private fun Modifier.selectionMask(position: () -> Float, active: Boolean, color: Color = Color.Transparent): Modifier =
    drawWithCache {
        val path = Path()
        val radius = CornerRadius(23.dp.toPx())
        onDrawWithContent {
            path.reset()
            path.addRoundRect(RoundRect(navigationCapsuleSelectionBounds(size.width, size.height, position()), radius))
            if (active) drawPath(path, color)
            clipPath(path, if (active) ClipOp.Intersect else ClipOp.Difference) { this@onDrawWithContent.drawContent() }
        }
    }

@Composable
private fun NavigationCapsuleLabels(color: Color, active: Boolean, modifier: Modifier) {
    Row(modifier.clearAndSetSemantics {}) {
        NavigationCapsuleLabel("首页", Icons.Default.Home, color, active, Modifier.weight(1f))
        NavigationCapsuleLabel("我的", Icons.Default.Person, color, active, Modifier.weight(1f))
    }
}

@Composable
private fun NavigationCapsuleLabel(label: String, icon: ImageVector, color: Color, active: Boolean, modifier: Modifier) {
    Column(modifier.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun NavigationCapsuleTarget(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxHeight().semantics {
            contentDescription = label
            selected = isSelected
            role = Role.Tab
        },
        onClick = onClick,
        shape = RoundedCornerShape(23.dp),
        color = Color.Transparent,
    ) { Box(Modifier.fillMaxSize()) }
}
