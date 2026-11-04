package com.musicone

import com.musicone.source.sdk.SourceTrackBadge
import org.json.JSONObject

/** 展示资料独立于权益事实持久化，缺包和离线仍可读取。 */
internal fun SourceTrackBadge.toStoredBadgeJson(): JSONObject = JSONObject().put("text", text).apply {
    lightTextArgb?.let { put("lightTextArgb", it) }
    lightBackgroundArgb?.let { put("lightBackgroundArgb", it) }
    darkTextArgb?.let { put("darkTextArgb", it) }
    darkBackgroundArgb?.let { put("darkBackgroundArgb", it) }
}

internal fun JSONObject.toStoredBadge(): SourceTrackBadge = SourceTrackBadge(
    text = optString("text"),
    lightTextArgb = badgeColor("lightTextArgb"),
    lightBackgroundArgb = badgeColor("lightBackgroundArgb"),
    darkTextArgb = badgeColor("darkTextArgb"),
    darkBackgroundArgb = badgeColor("darkBackgroundArgb"),
)

private fun JSONObject.badgeColor(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key).takeIf { it in 0L..0xFFFFFFFFL } else null
