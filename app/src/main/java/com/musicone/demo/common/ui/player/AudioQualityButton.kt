package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Stable
internal class PlayerQualityMenuState {
    var expanded by mutableStateOf(false)
        private set
    var trackId by mutableStateOf<String?>(null)
        private set
    var anchor by mutableStateOf(Rect.Zero)
        private set
    var triggerOrigin by mutableStateOf(Offset.Zero)
        private set
    private var selectAction: (AudioQuality) -> Unit = {}

    fun open(trackId: String, anchor: Rect, triggerOrigin: Offset, onSelect: (AudioQuality) -> Unit) {
        this.trackId = trackId
        this.anchor = anchor
        this.triggerOrigin = triggerOrigin
        selectAction = onSelect
        expanded = true
    }

    fun dismiss() { expanded = false }

    fun select(quality: AudioQuality) = selectAction(quality)
}

internal val LocalPlayerQualityMenu = staticCompositionLocalOf<PlayerQualityMenuState?> { null }

@Composable
internal fun AudioQualityButton(trackId: String, source: MusicSource, current: AudioQuality?,
    onSelect: (AudioQuality) -> Unit, modifier: Modifier = Modifier) {
    val playerInk = LocalContentColor.current
    val menu = LocalPlayerQualityMenu.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var pressOrigin by remember(trackId) { mutableStateOf<Offset?>(null) }
    IconButton(
        onClick = { menu?.open(trackId, anchor, pressOrigin ?: anchor.center, onSelect) },
        enabled = menu != null,
        modifier = modifier
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .pointerInput(trackId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressOrigin = anchor.topLeft + down.position
                }
            }
            .semantics {
                contentDescription = "当前${current?.displayLabel(source) ?: "音质未知"}，点击选择音质"
            },
    ) {
        Crossfade(
            targetState = current?.displayCompactLabel(source) ?: "音质",
            modifier = Modifier.width(44.dp),
            animationSpec = musicMotion(280),
            label = "音质标签切换",
        ) { label ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = playerInk)
            }
        }
    }
}

@Composable
internal fun PlayerQualityMenuOverlay(state: PlayerQualityMenuState, player: MusicOneUiState,
    onRetry: () -> Unit, motion: PageMotion, backdropLayer: GraphicsLayer) {
    val track = player.currentTrack
    LaunchedEffect(state.expanded, track?.id) { if (state.expanded && track != null) onRetry() }
    LaunchedEffect(track?.id) { if (state.expanded && state.trackId != track?.id) state.dismiss() }
    if (state.expanded) BackHandler(onBack = state::dismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
            var retainedQualities by remember(track?.id) { mutableStateOf(listOfNotNull(player.activeQuality)) }
            val resolvedQualities = visiblePlayerQualities(player.availableQualities)
            if (resolvedQualities.isNotEmpty()) retainedQualities = resolvedQualities
            val visibleQualities = resolvedQualities.ifEmpty { if (player.qualityLoading) retainedQualities else emptyList() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val maximumMenuHeight = maxHeight - 24.dp
            val menuWidth = minOf(292.dp, maxWidth - 24.dp)
            var measuredHeight by remember { mutableStateOf(0.dp) }
            val messageRows = if (player.qualityError != null) 1 else 0
            val menuHeight = (104 + visibleQualities.size * 58 + messageRows * 32).dp.coerceAtMost(338.dp)
            val x = with(density) { ((state.anchor.center.x - motion.hostBounds.left).toDp() - menuWidth / 2)
                .coerceIn(12.dp, maxWidth - menuWidth - 12.dp) }
            val actualHeight = measuredHeight.takeIf { it > 0.dp } ?: menuHeight
            val preferredY = with(density) { (state.anchor.top - motion.hostBounds.top).toDp() } - actualHeight - 8.dp
            val y = preferredY.coerceIn(12.dp, (maxHeight - actualHeight - 12.dp).coerceAtLeast(12.dp))
            val transformOrigin = qualityMenuTransformOrigin(state.triggerOrigin, motion.hostBounds)
        AnimatedVisibility(
            visible = state.expanded,
            enter = fadeIn(musicMotion(220)) + scaleIn(
                musicMotion(280), initialScale = .84f, transformOrigin = transformOrigin,
            ),
            exit = fadeOut(musicMotion(180)) + scaleOut(
                musicMotion(220), targetScale = .92f, transformOrigin = transformOrigin,
            ),
        ) {
            val outsideInteraction = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().clickable(outsideInteraction, indication = null, onClick = state::dismiss)) {
                MusicOneBackdropGlass(
                    backdropLayer = backdropLayer,
                    backdropBounds = motion.hostBounds,
                    modifier = Modifier
                        .offset(x, y).width(menuWidth)
                        .onSizeChanged { measuredHeight = with(density) { it.height.toDp() } }
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    shape = RoundedCornerShape(28.dp),
                    blurRadius = 24.dp,
                    containerColor = Color(0xFF16201F).copy(alpha = .58f),
                    fallbackColor = Color(0xEE192321),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .2f)),
                ) {
                    Column(Modifier.heightIn(max = maximumMenuHeight).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp)) {
                        Text("选择音质", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        Spacer(Modifier.height(6.dp))
                        player.qualityError?.let { error ->
                            Text("查询或切换失败：$error", color = Color(0xFFFFB4AB), fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp).clickable(onClick = onRetry))
                        }
                        val actualAudio by PlaybackAudioInfo.current.collectAsState()
                        val usbOutput by UsbAudioOutputStatus.current.collectAsState()
                        val bluetoothDevice by BluetoothAudioRoute.deviceName.collectAsState()
                        PLAYER_AUDIO_QUALITIES.forEach { quality ->
                            AnimatedVisibility(quality in visibleQualities,
                                enter = expandVertically(musicMotion(300)) + fadeIn(musicMotion(240)) + slideInVertically(musicMotion(300)) { it / 5 },
                                exit = shrinkVertically(musicMotion(260)) + fadeOut(musicMotion(180)) + slideOutVertically(musicMotion(260)) { -it / 5 }) {
                            QualityMenuRow(
                                quality, track?.source ?: MusicSource.QQ,
                                selected = quality == player.activeQuality,
                                enabled = !player.qualityChanging && quality in resolvedQualities,
                                deviceName = qualityOutputDeviceName(usbOutput, bluetoothDevice)
                                    ?.takeIf { quality == player.activeQuality },
                                status = when {
                                    player.qualityChanging && player.qualityTarget == quality -> "正在切换…"
                                    quality == player.activeQuality ->
                                        actualAudio?.takeIf { it.first == track?.previewUrl }?.second.orEmpty()
                                    else -> quality.displayDescription()
                                },
                            ) { state.select(quality) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun qualityMenuTransformOrigin(trigger: Offset, host: Rect): TransformOrigin {
    if (host.width <= 0f || host.height <= 0f) return TransformOrigin.Center
    return TransformOrigin(
        pivotFractionX = ((trigger.x - host.left) / host.width).coerceIn(0f, 1f),
        pivotFractionY = ((trigger.y - host.top) / host.height).coerceIn(0f, 1f),
    )
}

@Composable
private fun QualityMenuRow(quality: AudioQuality, source: MusicSource, selected: Boolean, enabled: Boolean,
    deviceName: String?,
    status: String,
    onClick: () -> Unit) {
    val alpha = if (enabled) 1f else .38f
    val label = buildAnnotatedString {
        withStyle(
            MaterialTheme.typography.bodyMedium.copy(
                color = Color.White.copy(alpha = alpha),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ).toSpanStyle(),
        ) {
            append(quality.displayLabel(source))
        }
        deviceName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
            withStyle(
                MaterialTheme.typography.labelSmall.copy(
                    color = Color.White.copy(alpha = alpha * .68f),
                    fontWeight = FontWeight.Normal,
                ).toSpanStyle(),
            ) {
                append(" · ")
                append(name)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(58.dp)
            .background(if (selected) Color.White.copy(alpha = .14f) else Color.Transparent,
                RoundedCornerShape(18.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Crossfade(label, animationSpec = musicMotion(220), label = "输出设备名称") {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Crossfade(status, animationSpec = musicMotion(220), label = "实际音频参数") {
                Text(
                    it,
                    color = Color.White.copy(alpha = alpha * .6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(24.dp).background(
            if (selected) Color(0xFF31D6A0) else Color.White.copy(alpha = .08f), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color(0xFF0E3026))
        }
    }
}
