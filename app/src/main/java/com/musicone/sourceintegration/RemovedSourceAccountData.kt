package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource

/** 真正删除时清除宿主遗留的会话副本，避免重新导入又从 0.2.0 数据恢复登录。 */
internal fun clearRemovedSourceLegacyAccount(context: Context, sourceId: String) {
    val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    val keys = removedSourceAccountKeys(sourceId, preferences.all.keys)
    if (keys.isEmpty()) return
    val editor = preferences.edit()
    keys.forEach(editor::remove)
    check(editor.commit()) { "无法清除旧音源登录数据" }
}

internal fun removedSourceAccountKeys(sourceId: String, keys: Set<String>): Set<String> {
    val prefix = "session_${MusicSource.fromId(sourceId).name.lowercase()}_"
    val legacyNetease = if (sourceId == MusicSource.NETEASE.id) setOf(
        "netease_cookie", "netease_device_id", "user_id", "nickname", "avatar_url",
    ) else emptySet()
    return keys.filterTo(mutableSetOf()) { it.startsWith(prefix) || it in legacyNetease }
}
