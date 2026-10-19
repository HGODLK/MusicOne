package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CacheManagementEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<CacheUsage?>(null) }
    var maximum by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        val disk = withContext(Dispatchers.IO) { MusicDiskCache.get(context) }
        disk.audioRevision.collect {
            usage = withContext(Dispatchers.IO) { disk.usage() }
            maximum = disk.limits.value.maximum
        }
    }
    LibraryActionRow("缓存管理", usage?.let { "已用 ${cacheSizeLabel(it.total)} · ${cacheMaximumLabel(maximum ?: 0)}" }
        ?: "缓存歌曲与容量设置", onClick = onOpen)
}

@Composable
internal fun CacheCapacitySettings(state: MusicCacheState, onCapacity: (Boolean, CacheCapacity) -> Unit) {
    LibrarySettingsGroup("容量设置") {
        CacheCapacityRow("总缓存", "所有歌曲共用的缓存额度", state.limits.total) { onCapacity(false, it) }
        CacheCapacityRow("独立缓存", "保护我喜欢和综合歌单中的歌曲", state.limits.independent) { onCapacity(true, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("实际最大容量", style = MaterialTheme.typography.bodyMedium)
            Text(cacheMaximumLabel(state.limits.maximum, withPrefix = false), style = MaterialTheme.typography.bodyMedium)
        }
        Text(state.limits.protectionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CacheCapacityRow(title: String, description: String, capacity: CacheCapacity, select: (CacheCapacity) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlaybackSettingsMenuButton(capacity.label, title, CacheCapacity.entries.map { choice ->
            PlaybackSettingsMenuOption(choice.label, capacity == choice) { select(choice) }
        })
    }
}

@Composable
internal fun CacheUsageSettings(state: MusicCacheState, onClear: (Boolean) -> Unit) {
    LibrarySettingsGroup("缓存占用") {
        Text(if (state.initialized) cacheSizeLabel(state.usage.total) else "—", style = MaterialTheme.typography.headlineLarge)
        Text(cacheMaximumLabel(state.limits.maximum), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("其中受保护 ${if (state.initialized) cacheSizeLabel(state.usage.protected) else "—"}", style = MaterialTheme.typography.bodyMedium)
        Text("未受保护 ${if (state.initialized) cacheSizeLabel(state.usage.ordinary) else "—"}", style = MaterialTheme.typography.bodyMedium)
        PlaybackSettingsMenuButton("清理缓存", "清理缓存", listOf(
            PlaybackSettingsMenuOption("清除未受保护缓存", false) { onClear(false) },
            PlaybackSettingsMenuOption("清除所有缓存", false) { onClear(true) },
        ))
    }
}

internal fun cacheSizeLabel(bytes: Long): String = if (bytes >= 1_073_741_824L)
    "%.2f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)

internal fun cacheMaximumLabel(bytes: Long, withPrefix: Boolean = true): String = when (bytes) {
    0L -> if (withPrefix) "新增缓存已关闭" else "不缓存"
    Long.MAX_VALUE -> "无上限"
    else -> (if (withPrefix) "实际容量上限 " else "") + cacheSizeLabel(bytes)
}
