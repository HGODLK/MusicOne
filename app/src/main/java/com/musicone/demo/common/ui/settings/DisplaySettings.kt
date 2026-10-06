package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DisplaySettings() {
    val options = ExperiencePreferences.options
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("显示与动态", style = MaterialTheme.typography.headlineSmall)
        AnimationFrameRateSettings()
        DisplaySetting("始终使用深色模式", "打开后始终为深色，关闭后跟随系统", options.alwaysDark) {
            ExperiencePreferences.update(options.copy(alwaysDark = it))
        }
        DisplaySetting("关闭模糊效果", "使用清晰底色，降低图形负担", options.disableBlur) {
            ExperiencePreferences.update(options.copy(disableBlur = it))
        }
        DisplaySetting("关闭未播放歌词模糊", "仅影响未播放歌词，不影响播放控件模糊", options.disableLyricBlur) {
            ExperiencePreferences.update(options.copy(disableLyricBlur = it))
        }
        DisplaySetting("减弱动态效果", "减少页面位移和持续流动效果", options.reduceMotion) {
            ExperiencePreferences.update(options.copy(reduceMotion = it))
        }
        DisplaySetting("关闭推荐页音乐卡片彩色效果", "保留封面，卡片背景使用中性色", options.disableCardColors) {
            ExperiencePreferences.update(options.copy(disableCardColors = it))
        }
    }
}

@Composable
private fun DisplaySetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MusicOneSettingsSwitch(checked)
    }
}
