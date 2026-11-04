package com.musicone.source.qq

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.qq.internal.*

/** 平台音质设置独立展示，不混入登录表单。 */
@Composable
internal fun QqQualitySettings(services: QqServices) {
    var selected by remember { mutableStateOf(services.qualityPreferences.getString("preferred_qq", AudioQuality.EXHIGH.name)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("默认播放音质", style = MaterialTheme.typography.titleMedium)
        QQ_AUDIO_QUALITIES.forEach { quality ->
            Row {
                RadioButton(selected == quality.name, {
                    services.qualityPreferences.edit().putString("preferred_qq", quality.name).apply()
                    selected = quality.name
                })
                Text(quality.label, Modifier.padding(top = 12.dp))
            }
        }
    }
}
