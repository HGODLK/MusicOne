package com.musicone.demo

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** 只装饰同一条媒体通知；封面异步回调与歌词更新都交回 Media3 发布。 */
@RequiresApi(26)
@androidx.annotation.OptIn(UnstableApi::class)
internal class HyperOsMediaNotificationProvider(
    private val context: Context,
    private val delegate: MediaNotification.Provider,
) : MediaNotification.Provider by delegate {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: MediaNotification.Provider.Callback? = null
    private var closed = false
    private var revision = 0L
    private var latestSubmission: MediaNotification? = null
    private var pendingInspection: Runnable? = null
    private val handoff = HyperOsNotificationHandoff<MediaNotification, Bundle>(
        enqueue = { handler.postDelayed(it, HYPER_OS_NOTIFICATION_BATCH_MS) },
        remove = { handler.removeCallbacks(it) },
        decorate = { media, extras ->
            try { decorate(media, extras) } catch (error: RuntimeException) {
                HyperOsIslandDiagnostics.failed("装饰媒体通知失败（${error.javaClass.simpleName}）")
                media
            }
        },
        equivalent = ::sameFocusPresentation,
        publish = { value ->
            val target = callback
            if (target == null) HyperOsIslandDiagnostics.queued() else {
                try {
                    target.onNotificationChanged(value)
                    submitted(value)
                } catch (error: RuntimeException) {
                    HyperOsIslandDiagnostics.failed("交付媒体通知失败（${error.javaClass.simpleName}）")
                }
            }
        },
    )

    init {
        // 清理旧版本独立歌词通知，不能取消 Media3 的媒体通知。
        context.getSystemService(NotificationManager::class.java).cancel(1703)
    }

    override fun createNotification(session: MediaSession, buttons: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification {
        this.callback = callback
        val request = ++revision
        val trackId = session.player.currentMediaItem?.mediaId
        val generated = delegate.createNotification(session, buttons, HyperOsPlaybackActionFactory(actionFactory),
            MediaNotification.Provider.Callback { updated ->
                // 封面解码可在后台完成，统一回到服务主线程检查代次与更新装饰。
                handler.post {
                    if (!closed && request == revision) {
                        handoff.mediaUpdated(trackId, updated)
                    }
                }
            })
        return handoff.mediaCreated(trackId, generated).also(::submitted)
    }

    private fun decorate(value: MediaNotification, extras: Bundle): MediaNotification {
        ensureHyperOsIslandChannel(context)
        return hyperOsDecoratedNotification(context, value, extras, HYPER_OS_CHANNEL_ID)
    }

    private fun submitted(value: MediaNotification) {
        val extras = value.notification.extras
        if (!extras.containsKey(HYPER_OS_CUSTOM_PARAM) && !extras.containsKey(HYPER_OS_STANDARD_PARAM)) return
        latestSubmission = value
        HyperOsIslandDiagnostics.submitted(extras.containsKey(HYPER_OS_STANDARD_PARAM))
        if (pendingInspection != null) return
        val inspection = Runnable {
            pendingInspection = null
            val expected = latestSubmission ?: return@Runnable
            try {
                val active = context.getSystemService(NotificationManager::class.java).activeNotifications
                    .firstOrNull { it.id == expected.notificationId }?.notification
                HyperOsIslandDiagnostics.registered(active != null && active.channelId == HYPER_OS_CHANNEL_ID &&
                    focusPayload(active) == focusPayload(expected.notification))
            } catch (error: RuntimeException) {
                HyperOsIslandDiagnostics.failed("读取系统通知状态失败（${error.javaClass.simpleName}）")
            }
        }
        pendingInspection = inspection
        handler.postDelayed(inspection, 300L)
    }

    fun update(extras: Bundle?, trackId: String? = null) {
        if (closed) return
        if (extras == null) {
            pendingInspection?.let(handler::removeCallbacks)
            pendingInspection = null
            latestSubmission = null
        } else HyperOsIslandDiagnostics.queued()
        handoff.overlayUpdated(trackId, extras)
    }

    fun close() {
        closed = true
        handoff.close()
        handler.removeCallbacksAndMessages(null)
        callback = null
    }

    /** 比较更新序号与媒体资料，完整歌词和逐秒进度不能被摘要去重吞掉。 */
    private fun sameFocusPresentation(before: MediaNotification, after: MediaNotification): Boolean {
        val a = before.notification
        val b = after.notification
        val payload = focusPayload(a) ?: return before === after
        if (focusPayload(b) != payload || a.channelId != b.channelId) return false
        @Suppress("DEPRECATION")
        fun cover(value: Notification): Icon? = value.extras.getBundle("miui.focus.pics")
            ?.getParcelable(HYPER_OS_COVER_KEY)
        fun controls(value: Notification) = value.actions.orEmpty().map { it.title.toString() to it.actionIntent }
        return cover(a) === cover(b) && a.getLargeIcon() === b.getLargeIcon() && controls(a) == controls(b) &&
            a.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                b.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() &&
            a.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                b.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() &&
            (a.flags and Notification.FLAG_ONGOING_EVENT) == (b.flags and Notification.FLAG_ONGOING_EVENT)
    }

}

private fun focusPayload(value: Notification): String? =
    value.extras.getString(HYPER_OS_CUSTOM_PARAM) ?: value.extras.getString(HYPER_OS_STANDARD_PARAM)

/** recoverBuilder 会修改传入对象，必须先复制，避免连上一帧一起改写而吞掉更新。 */
@RequiresApi(26)
@androidx.annotation.OptIn(UnstableApi::class)
internal fun hyperOsDecoratedNotification(context: Context, value: MediaNotification,
    extras: Bundle, channelId: String): MediaNotification {
    val clean = value.notification.clone().apply {
        // 切换协议不能遗留另一种模式的布局键，否则系统仍可能选择旧通道。
        HYPER_OS_OVERLAY_KEYS.forEach(this.extras::remove)
    }
    val notification = Notification.Builder.recoverBuilder(context, clean)
        .setChannelId(channelId).setOnlyAlertOnce(true)
        .addExtras(hyperOsPlaybackExtras(context, extras, value.notification)).build()
    return MediaNotification(value.notificationId, notification)
}
