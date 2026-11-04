package com.musicone

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 手机上胶囊让位给迷你播放器，平板空间充裕保持原尺寸。 */
internal data class NavigationCapsuleMetrics(
    val width: Dp,
    val innerHeight: Dp,
    val innerPadding: Dp,
    val topGap: Dp,
    val bottomGap: Dp,
    val outerRadius: Dp,
    val selectionRadius: Dp,
    val iconSize: Dp,
    val labelGap: Dp,
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
)

private val PhoneNavigationCapsule = NavigationCapsuleMetrics(
    width = 222.dp, innerHeight = 48.dp, innerPadding = 4.dp, topGap = 4.dp, bottomGap = 6.dp,
    outerRadius = 26.dp, selectionRadius = 22.dp,
    iconSize = 18.dp, labelGap = 2.dp, fontSize = 11.sp, lineHeight = 13.sp,
)

private val TabletNavigationCapsule = NavigationCapsuleMetrics(
    width = 246.dp, innerHeight = 52.dp, innerPadding = 5.dp, topGap = 6.dp, bottomGap = 6.dp,
    outerRadius = 28.dp, selectionRadius = 23.dp,
    iconSize = 20.dp, labelGap = 2.dp, fontSize = 11.sp, lineHeight = 13.sp,
)

internal fun navigationCapsuleMetrics(phone: Boolean): NavigationCapsuleMetrics =
    if (phone) PhoneNavigationCapsule else TabletNavigationCapsule

internal fun navigationCapsuleSelectionBounds(width: Float, height: Float, position: Float): Rect {
    val itemWidth = width / MusicOnePage.entries.size
    val left = itemWidth * position.coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
    return Rect(left, 0f, left + itemWidth, height)
}

/** 选中态跟随平台主题，不被“我的”页面用于背景对比度的黑白前景覆盖。 */
internal fun navigationCapsuleSelectionColor(neutral: Boolean, container: Color): Color =
    if (neutral) container else container.copy(alpha = .56f)

/** 两层图标共享同一条圆角裁切边界；进度只在绘制时读取，点击和拖动都不会整枚跳色。 */
@Composable
internal fun NavigationCapsuleSelection(
    page: MusicOnePage,
    position: () -> Float,
    activeColor: Color,
    inactiveColor: Color,
    selectionColor: Color,
    metrics: NavigationCapsuleMetrics,
    onHome: () -> Unit,
    onMy: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        NavigationCapsuleLabels(
            inactiveColor, false, metrics,
            Modifier.matchParentSize().selectionMask(position, metrics.selectionRadius, active = false),
        )
        NavigationCapsuleLabels(
            activeColor, true, metrics,
            Modifier.matchParentSize().selectionMask(position, metrics.selectionRadius, active = true, selectionColor),
        )
        // 点击区域只保留一份语义，装饰层不重复朗读，命中范围不受颜色裁切影响。
        Row(Modifier.matchParentSize()) {
            NavigationCapsuleTarget("首页", page == MusicOnePage.HOME, metrics.selectionRadius, Modifier.weight(1f), onHome)
            NavigationCapsuleTarget("我的", page == MusicOnePage.MY, metrics.selectionRadius, Modifier.weight(1f), onMy)
        }
    }
}

private fun Modifier.selectionMask(position: () -> Float, cornerRadius: Dp, active: Boolean, color: Color = Color.Transparent): Modifier =
    drawWithCache {
        val path = Path()
        val radius = CornerRadius(cornerRadius.toPx())
        onDrawWithContent {
            path.reset()
            path.addRoundRect(RoundRect(navigationCapsuleSelectionBounds(size.width, size.height, position()), radius))
            if (active) drawPath(path, color)
            clipPath(path, if (active) ClipOp.Intersect else ClipOp.Difference) { this@onDrawWithContent.drawContent() }
        }
    }

@Composable
private fun NavigationCapsuleLabels(color: Color, active: Boolean, metrics: NavigationCapsuleMetrics, modifier: Modifier) {
    Row(modifier.clearAndSetSemantics {}) {
        NavigationCapsuleLabel("首页", Icons.Default.Home, color, active, metrics, Modifier.weight(1f))
        NavigationCapsuleLabel("我的", Icons.Default.Person, color, active, metrics, Modifier.weight(1f))
    }
}

@Composable
private fun NavigationCapsuleLabel(
    label: String, icon: ImageVector, color: Color, active: Boolean,
    metrics: NavigationCapsuleMetrics, modifier: Modifier,
) {
    Column(modifier.fillMaxHeight(), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(metrics.iconSize))
        Text(label, color = color, fontSize = metrics.fontSize, lineHeight = metrics.lineHeight, maxLines = 1,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = metrics.labelGap))
    }
}

@Composable
private fun NavigationCapsuleTarget(label: String, isSelected: Boolean, cornerRadius: Dp, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxHeight().semantics {
            contentDescription = label
            selected = isSelected
            role = Role.Tab
        },
        onClick = onClick,
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Transparent,
    ) { Box(Modifier.fillMaxSize()) }
}
