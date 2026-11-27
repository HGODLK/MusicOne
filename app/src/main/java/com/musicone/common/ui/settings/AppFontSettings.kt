package com.musicone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun AppFontSettings(onOpenCustomFonts: () -> Unit = {}) {
    val context = LocalContext.current
    val selected = ExperiencePreferences.options.fontMode
    SettingsMenuRow("字体", selected.label,
        AppFontMode.entries.map { mode ->
            PlaybackSettingsMenuOption(mode.label, mode == selected, description = when (mode) {
                AppFontMode.DEFAULT -> "跟随应用默认字体"
                AppFontMode.SANS_SERIF -> "歌词全部使用更纱黑体"
                AppFontMode.SYSTEM -> "全局使用系统字体"
                AppFontMode.CUSTOM -> "分别自定义各语系界面、歌词与横屏时钟字体"
            }) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(fontMode = mode))
            }
        })
    SettingsReveal(selected == AppFontMode.CUSTOM) {
        CustomFontStore.initialize(context)
        val customConfig = CustomFontStore.config
        CustomFontStore.revision
        val activeTask = CustomFontImportSession.activeTask
        val subtitle = when {
            activeTask != null -> activeTask.summary
            customConfig.records.isEmpty() && !customConfig.hasActiveOverrides() ->
                "未做修改时按默认显示，点此按语系导入与配置"
            else -> "已导入 ${customConfig.records.size} 个字体 · 界面、歌词与横屏时钟"
        }
        SettingsEntryRow("自定义字体配置", subtitle, trailing = {
            Text(" ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }, onClick = onOpenCustomFonts)
    }
}
