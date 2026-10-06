package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaybackSettings(source: MusicSource) {
    val context = LocalContext.current
    val preferences = remember { PlaybackQualityPreferences(context) }
    val preferred by remember(source) { preferences.observe(source) }.collectAsState(preferences.read(source))
    remember { PlaybackOptions.initialize(context); true }
    val usbStatus by UsbAudioOutputStatus.current.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("播放与音质", style = MaterialTheme.typography.titleLarge)
        PlaybackSettingsMenuButton(
            label = "首选音质 · ${preferred.displayLabel(source)}",
            title = "首选音质",
            options = PLAYER_AUDIO_QUALITIES.map { quality ->
                PlaybackSettingsMenuOption(quality.displayLabel(source), quality == preferred) {
                    preferences.save(source, quality)
                }
            },
        )
        PlaybackSettingsMenuButton(
            label = "移动网络 · ${PlaybackOptions.mobileQuality?.displayLabel(source) ?: "跟随首选"}",
            title = "移动网络音质上限",
            options = listOf(PlaybackSettingsMenuOption("跟随首选", PlaybackOptions.mobileQuality == null) {
                PlaybackOptions.mobile(null)
            }) + PLAYER_AUDIO_QUALITIES.map { quality ->
                PlaybackSettingsMenuOption(quality.displayLabel(source), quality == PlaybackOptions.mobileQuality) {
                    PlaybackOptions.mobile(quality)
                }
            },
        )
        PlaybackSettingsMenuButton(
            label = "睡眠定时 · ${if (PlaybackSleepTimer.afterTrack) "本首结束" else if (PlaybackSleepTimer.deadline > 0) "已开启" else "关闭"}",
            title = "睡眠定时",
            options = listOf(0, -1, 15, 30, 60).map { minutes ->
                PlaybackSettingsMenuOption(
                    label = when (minutes) { 0 -> "关闭"; -1 -> "本首结束"; else -> "$minutes 分钟" },
                    selected = (minutes == 0 && !PlaybackSleepTimer.afterTrack && PlaybackSleepTimer.deadline <= 0) ||
                        (minutes == -1 && PlaybackSleepTimer.afterTrack),
                ) { PlaybackSleepTimer.set(minutes) }
            },
        )
        Row(
            Modifier.fillMaxWidth().toggleable(
                PlaybackOptions.keepScreenOn,
                role = Role.Switch,
                onValueChange = PlaybackOptions::screenOn,
            ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播放页播放时屏幕常亮", Modifier.weight(1f))
            MusicOneSettingsSwitch(PlaybackOptions.keepScreenOn)
        }
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = PlaybackOptions.usbLosslessOutput,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        PlaybackOptions.usbLossless(enabled)
                        if (enabled) UsbAudioPermissionCoordinator.prepareForAutomaticSelection()
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("USB 音频设备无损输出")
                Text(
                    usbOutputDescription(PlaybackOptions.usbLosslessOutput, usbStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MusicOneSettingsSwitch(PlaybackOptions.usbLosslessOutput)
        }
    }
}

private fun usbOutputDescription(enabled: Boolean, status: UsbAudioOutputSnapshot): String {
    if (!enabled) return "MusicOne 将会接管 USB 音频设备，其他软件无法播放音频"
    val format = listOfNotNull(
        status.bitDepth.takeIf { it > 0 }?.let { "${it}bit" },
        status.sampleRate.takeIf { it > 0 }?.let {
            "${java.math.BigDecimal(it).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
        },
    ).joinToString(" / ")
    val device = status.deviceName?.takeIf(String::isNotBlank)
    val route = when (status.route) {
        UsbAudioOutputRoute.SYSTEM_BIT_PERFECT -> "系统 Bit-Perfect"
        UsbAudioOutputRoute.DIRECT_UAC2 -> "UAC2 独占"
        UsbAudioOutputRoute.FALLBACK -> "系统兼容输出"
        UsbAudioOutputRoute.PERMISSION_REQUIRED -> "等待授权"
        UsbAudioOutputRoute.WAITING_FOR_DEVICE -> "等待设备"
        UsbAudioOutputRoute.READY -> "设备就绪"
        UsbAudioOutputRoute.UNSUPPORTED_ANDROID -> "系统兼容输出"
        UsbAudioOutputRoute.DISABLED -> null
    }
    return listOfNotNull(device, route, format.takeIf(String::isNotBlank))
        .distinct()
        .joinToString(" · ")
        .ifBlank { "已开启，等待播放或连接设备" }
}

@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("关于", style = MaterialTheme.typography.titleLarge)
        Text("MusicOne · $version")
        Text("开发者", style = MaterialTheme.typography.titleMedium)
        DeveloperCredits()
    }
}
