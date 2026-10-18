package com.musicone.demo

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** 只观察默认通知的生成与系统登记，不改写通知、令牌或封面异步回调结果。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SystemMediaNotificationDiagnostics(
    private val context: Context,
    private val foreground: () -> Boolean,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider.Builder(context).build(),
) : MediaNotification.Provider by delegate {
    private val handler = Handler(Looper.getMainLooper())
    private var notificationId = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
    private var pendingInspection: Runnable? = null
    private var closed = false

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val callback = MediaNotification.Provider.Callback { updated ->
            onNotificationChangedCallback.onNotificationChanged(updated)
            generated(updated, artworkUpdated = true)
        }
        return delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory, callback)
            .also { generated(it, artworkUpdated = false) }
    }

    private fun generated(notification: MediaNotification, artworkUpdated: Boolean) {
        if (closed) return
        notificationId = notification.notificationId
        Log.i(TAG, "通知已生成 id=$notificationId 封面回调=$artworkUpdated")
        inspectAfterUpdate()
    }

    fun inspectAfterUpdate() {
        if (closed) return
        pendingInspection?.let(handler::removeCallbacks)
        val inspection = Runnable {
            pendingInspection = null
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channelId = delegate.notificationChannelInfo.id
                val importance = if (Build.VERSION.SDK_INT >= 26) {
                    manager.getNotificationChannel(channelId)?.importance
                } else null
                val active = manager.activeNotifications.firstOrNull { it.id == notificationId }
                val hasToken = active?.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
                Log.i(TAG, "通知系统快照 id=$notificationId 系统已登记=${active != null}" +
                    " 媒体令牌=$hasToken 前台服务=${foreground()}" +
                    " 应用通知开关=${NotificationManagerCompat.from(context).areNotificationsEnabled()}" +
                    " 渠道重要性=${importance ?: -1}")
            }.onFailure { Log.w(TAG, "通知系统快照读取失败 类型=${it.javaClass.simpleName}") }
        }
        pendingInspection = inspection
        // 等 Media3 提交本轮通知后再查系统；只延后诊断，不延后通知或播放。
        handler.postDelayed(inspection, 500L)
    }

    fun close() {
        closed = true
        pendingInspection?.let(handler::removeCallbacks)
        pendingInspection = null
    }

    private companion object { const val TAG = "MusicOneMediaNotification" }
}
