package com.musicone.demo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.RequiresApi

internal const val HYPER_OS_CHANNEL_ID = "hyperos_island_lyrics_v1"

/** 开启设置时就准备类别，不让类别是否出现依赖首帧或封面加载成功。 */
@RequiresApi(26)
internal fun ensureHyperOsIslandChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(HYPER_OS_CHANNEL_ID) == null) {
        manager.createNotificationChannel(NotificationChannel(HYPER_OS_CHANNEL_ID, "超级岛歌词",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }
}
