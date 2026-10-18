package com.musicone.demo

import android.content.Intent
import android.os.Bundle

/** vivo 基础音乐接入；仍复用同一个 Media3 会话，不声明歌词、收藏和歌单能力。 */
internal object VivoSystemMedia {
    const val SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"
    const val SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    private const val BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"

    // 社区协议位：播放控制、歌曲信息、封面、进度；切歌沿用会话已有的可用命令。
    private const val BASIC_SUPPORT = 1L or (1L shl 1) or (1L shl 2) or (1L shl 4)

    fun browserIntent(intent: Intent?): Intent? =
        if (intent?.action == SERVICE_ACTION) Intent(intent).setAction(BROWSER_SERVICE_ACTION) else intent

    fun addBasicCapabilities(extras: Bundle) {
        // 使用 Long，Media3 转成旧版 MediaMetadata 时才会保留该自定义能力字段。
        extras.putLong(SUPPORT_EVENT, BASIC_SUPPORT)
    }
}
