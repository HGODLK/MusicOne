package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 一级分组“播放”：音质、播放行为、音频输出三个二级分组。 */
@Composable
internal fun PlaybackSettings(source: MusicSource) {
    val context = LocalContext.current
    val preferences = remember { PlaybackQualityPreferences(context) }
    val preferred by remember(source) { preferences.observe(source) }.collectAsState(preferences.read(source))
    remember { PlaybackOptions.initialize(context); true }
    val sourceRegistry by com.musicone.sourceintegration.SourcePackageController.get(context).installed.collectAsState()
    // 目录就绪时显式重读同一原始档位，不能只等待移动上限偏好自身变化。
    val currentMobileQuality = PlaybackOptions.mobileQuality
    val mobileQuality = remember(sourceRegistry, currentMobileQuality) { currentMobileQuality }
    val usbStatus by UsbAudioOutputStatus.current.collectAsState()
    val qualities = playerQualitiesForSource(source)
    val dolby = DolbyPlaybackPolicy.eligibility
    SettingsSection("播放") {
        SettingsGroup("音质") {
            SettingsMenuRow(
                title = "首选音质",
                value = preferred.displayLabel(source),
                options = qualities.map { quality ->
                    PlaybackSettingsMenuOption(quality.displayLabel(source), quality == preferred,
                        enabled = quality.capabilityQuality != AudioQuality.DOLBY || dolby.allowed,
                        description = dolby.unavailableReason.takeIf { quality.capabilityQuality == AudioQuality.DOLBY }) {
                        if (DolbyPlaybackPolicy.permits(quality)) preferences.save(source, quality)
                    }
                },
            )
            SettingsMenuRow(
                title = "移动网络音质上限",
                value = mobileQuality?.displayLabel(source) ?: "跟随首选",
                options = listOf(PlaybackSettingsMenuOption("跟随首选", mobileQuality == null) {
                    PlaybackOptions.mobile(null)
                }) + qualities.map { quality ->
                    PlaybackSettingsMenuOption(quality.displayLabel(source), quality == mobileQuality,
                        enabled = quality.capabilityQuality != AudioQuality.DOLBY || dolby.allowed,
                        description = dolby.unavailableReason.takeIf { quality.capabilityQuality == AudioQuality.DOLBY }) {
                        if (DolbyPlaybackPolicy.permits(quality)) PlaybackOptions.mobile(quality)
                    }
                },
            )
        }
        SettingsGroup("播放行为") {
            SettingsMenuRow(
                title = "睡眠定时",
                value = if (PlaybackSleepTimer.afterTrack) "本首结束" else if (PlaybackSleepTimer.deadline > 0) "已开启" else "关闭",
                options = listOf(0, -1, 15, 30, 60).map { minutes ->
                    PlaybackSettingsMenuOption(
                        label = when (minutes) { 0 -> "关闭"; -1 -> "本首结束"; else -> "$minutes 分钟" },
                        selected = (minutes == 0 && !PlaybackSleepTimer.afterTrack && PlaybackSleepTimer.deadline <= 0) ||
                            (minutes == -1 && PlaybackSleepTimer.afterTrack),
                    ) { PlaybackSleepTimer.set(minutes) }
                },
            )
            SettingsSwitchRow("点播首页推荐不清空队列", "仅插入当前播放队列，不替换为猜你喜欢",
                PlaybackOptions.keepQueueOnHomeRecommendation, PlaybackOptions::keepQueueOnHomeRecommendation)
            FavoriteButtonSettings()
        }
        SettingsGroup("音频输出") {
            PlaybackOutputDetails(Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 12.dp),
                title = "当前输出")
            DevicePlaybackSettings()
            if (qualities.any { it.capabilityQuality == AudioQuality.DOLBY }) DolbyPlaybackSettings()
            LgHiResSettings()
            SettingsSwitchRow("USB 音频设备无损输出", usbOutputDescription(PlaybackOptions.usbLosslessOutput, usbStatus),
                PlaybackOptions.usbLosslessOutput) { enabled ->
                PlaybackOptions.usbLossless(enabled)
                if (enabled) UsbAudioPermissionCoordinator.prepareForAutomaticSelection()
            }
            UsbHardwareVolumeSetting()
        }
    }
}

/** 播放页常亮放在“显示 · 播放页”分组；选项可能先于播放设置组合，这里自行确保已读入。 */
@Composable
internal fun ScreenOnSetting() {
    val context = LocalContext.current
    remember { PlaybackOptions.initialize(context); true }
    SettingsSwitchRow("播放页保持常亮", "播放期间不自动息屏，OLED 屏长时间使用请注意", PlaybackOptions.keepScreenOn, PlaybackOptions::screenOn)
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
        UsbAudioOutputRoute.DIRECT_UAC2 -> if (status.converted) "UAC2 独占（兼容转换）" else "UAC2 独占"
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

/** 一级分组“关于”：版本信息、开发者与版本更新。 */
@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    SettingsSection("关于") {
        SettingsCard {
            Column(Modifier.padding(horizontal = SettingsRowInset, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MusicOne · $version", style = MaterialTheme.typography.titleMedium)
                SettingsOptimizationNotice()
            }
        }
        // 开发者条目是自带内边距的列表项，不再额外缩进。
        SettingsGroup("开发者") { DeveloperCredits() }
        VersionUpdateSettings()
    }
}
