package com.musicone.source.webdav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 与宿主歌曲行同一套尺寸：52dp 图标块、16dp 圆角、上下 9dp、64dp 起的分隔线。 */
@Composable
internal fun WebDavFolderRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading() else WebDavIconBlock(Icons.Rounded.Folder)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) trailing() else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    }
}

@Composable
internal fun WebDavIconBlock(icon: ImageVector) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 与宿主歌曲列表占位同尺寸，内容到达后原位淡出，不引起跳动。 */
@Composable
internal fun WebDavRowPlaceholder(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    Column(modifier.clearAndSetSemantics { }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(block))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth(.46f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(block))
                Box(Modifier.fillMaxWidth(.28f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(block))
            }
            Spacer(Modifier.size(48.dp))
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
    }
}

/** 公共 Empty 的底色固定为浅色，这里按当前主题自绘。 */
@Composable
internal fun WebDavEmpty(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) Text(message, Modifier.widthIn(max = 420.dp), fontSize = 13.sp, lineHeight = 20.sp,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Box(Modifier.padding(top = 6.dp)) { action() }
    }
}

/** 刷新中持续转动，结束时转完当前一圈再停，不会突然弹回。 */
@Composable
internal fun WebDavRefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val motion = LocalWebDavMotion.current
    val rotation = remember { Animatable(0f) }
    val active by rememberUpdatedState(refreshing)
    LaunchedEffect(motion) {
        if (motion.reduce) return@LaunchedEffect
        snapshotFlow { active }.collect { on ->
            if (!on) return@collect
            while (active) {
                rotation.animateTo(360f, tween(900, easing = LinearEasing))
                rotation.snapTo(0f)
            }
        }
    }
    IconButton(
        onClick = onClick,
        enabled = !refreshing,
        colors = IconButtonDefaults.iconButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.semantics { if (refreshing) stateDescription = "正在刷新" },
    ) {
        Icon(Icons.Rounded.Refresh, "刷新文件夹", Modifier.graphicsLayer { rotationZ = rotation.value })
    }
}

@Composable
internal fun WebDavTogglePill(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val motion = LocalWebDavMotion.current
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(colors.onSurface.copy(alpha = if (checked) .14f else .06f), motion.spec(220), label = "开关底色")
    Row(
        modifier.heightIn(min = 48.dp).clip(CircleShape)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp).clip(CircleShape).background(background)
            .heightIn(min = 36.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            checked,
            enter = expandHorizontally(motion.spec(220)) + fadeIn(motion.spec(180)),
            exit = shrinkHorizontally(motion.spec(180)) + fadeOut(motion.spec(140)),
        ) {
            Icon(Icons.Rounded.Check, null, Modifier.padding(end = 6.dp).size(18.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
    }
}

/** 两个图标之间的小幅切换反馈。 */
@Composable
internal fun WebDavSwapIcon(icon: ImageVector, description: String) {
    val motion = LocalWebDavMotion.current
    AnimatedContent(icon, transitionSpec = {
        (fadeIn(motion.spec(200)) + scaleIn(motion.spec(220), initialScale = .8f)) togetherWith
            (fadeOut(motion.spec(140)) + scaleOut(motion.spec(180), targetScale = .8f))
    }, label = "图标切换") { Icon(it, description) }
}

/** 底部浮动提示：不占列表位置，普通消息自动收起；忙碌优先显示。 */
@Composable
internal fun WebDavStatusPill(busy: String?, feedback: WebDavFeedback?, modifier: Modifier = Modifier, onDismiss: (Long) -> Unit) {
    val motion = LocalWebDavMotion.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(feedback?.id, busy == null) {
        val shown = feedback ?: return@LaunchedEffect
        if (busy != null) return@LaunchedEffect
        delay(if (shown.isError) 5_000 else 2_800)
        dismiss(shown.id)
    }
    val text = busy ?: feedback?.message
    // 退场期间保留最后一条文字，不先变空再收起。
    var lastText by remember { mutableStateOf("") }
    var lastBusy by remember { mutableStateOf(false) }
    if (text != null) SideEffect {
        lastText = text
        lastBusy = busy != null
    }
    AnimatedVisibility(
        text != null,
        modifier,
        enter = fadeIn(motion.spec(220)) + slideInVertically(motion.spec(280)) { it / 2 },
        exit = fadeOut(motion.spec(160)) + slideOutVertically(motion.spec(220)) { it / 2 },
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.clickable(enabled = busy == null && feedback != null) { feedback?.let { dismiss(it.id) } }
                    .heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (if (text != null) busy != null else lastBusy) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                AnimatedContent(text ?: lastText, transitionSpec = {
                    fadeIn(motion.spec(200)) togetherWith fadeOut(motion.spec(140))
                }, label = "提示文字") { Text(it, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
