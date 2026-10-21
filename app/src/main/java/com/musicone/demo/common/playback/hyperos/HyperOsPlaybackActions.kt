package com.musicone.demo

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import org.json.JSONArray
import org.json.JSONObject

private const val PLAYBACK_ACTION_KEY = "com.musicone.demo.hyperos.PLAYBACK_ACTION"
internal const val HYPER_OS_FOREGROUND_KEY = "com.musicone.demo.hyperos.FOREGROUND"
private const val PREVIOUS = "previous"
private const val PLAY_PAUSE = "play_pause"
private const val NEXT = "next"

/** 标记原有媒体动作，图标、可用状态及点击入口始终由 Media3 提供。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HyperOsPlaybackActionFactory(
    private val delegate: MediaNotification.ActionFactory,
) : MediaNotification.ActionFactory by delegate {
    override fun createMediaAction(session: MediaSession, icon: IconCompat, title: CharSequence,
        @Player.Command command: Int): NotificationCompat.Action {
        val action = delegate.createMediaAction(session, icon, title, command)
        val key = when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> PREVIOUS
            Player.COMMAND_PLAY_PAUSE -> PLAY_PAUSE
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> NEXT
            else -> return action
        }
        return NotificationCompat.Action.Builder(action)
            .addExtras(Bundle().apply { putString(PLAYBACK_ACTION_KEY, key) }).build()
    }
}

/** 每轮装饰都绑定本轮通知，异步封面和逐秒歌词更新不会带回旧的播放按钮。 */
internal fun hyperOsPlaybackExtras(context: android.content.Context, extras: Bundle, notification: Notification): Bundle {
    val actions = notification.actions.orEmpty().associateBy { it.extras.getString(PLAYBACK_ACTION_KEY) }
    val color = extras.getInt(HYPER_OS_FOREGROUND_KEY, HyperOsCardContrast.WHITE)
    val buttons = listOf(PREVIOUS to R.id.island_previous, PLAY_PAUSE to R.id.island_play_pause, NEXT to R.id.island_next)
    @Suppress("DEPRECATION")
    val template = extras.getParcelable<RemoteViews>(HYPER_OS_CARD_LAYOUT_KEYS.last())
        ?: return hyperOsStandardPlaybackExtras(extras, actions)
    val views = template.clone()
    buttons.forEach { (key, id) ->
        val action = actions[key]
        val icon = action?.getIcon()?.loadDrawable(context)?.mutate()
        val available = action?.actionIntent != null && icon != null
        views.setViewVisibility(id, if (available) View.VISIBLE else View.INVISIBLE)
        if (available) {
            val size = (28 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            requireNotNull(icon).apply { setTint(color); setBounds(0, 0, size, size); draw(Canvas(bitmap)) }
            views.setImageViewBitmap(id, bitmap)
            views.setContentDescription(id, action?.title)
            views.setOnClickPendingIntent(id, action?.actionIntent)
        }
    }
    notification.contentIntent?.let { views.setOnClickPendingIntent(R.id.island_metadata, it) }
    return Bundle(extras).apply { HYPER_OS_CARD_LAYOUT_KEYS.forEach { putParcelable(it, views) } }
}

/** 标准模板只引用原有媒体动作，切换展示方式不会创建第二套播放入口。 */
private fun hyperOsStandardPlaybackExtras(extras: Bundle, actions: Map<String?, Notification.Action>): Bundle {
    val payload = extras.getString(HYPER_OS_STANDARD_PARAM) ?: return extras
    val root = JSONObject(payload)
    val parameters = root.getJSONObject("param_v2")
    val links = JSONArray()
    val bindings = Bundle()
    listOf(PREVIOUS, PLAY_PAUSE, NEXT).forEach { key ->
        val action = actions[key]?.takeIf { it.actionIntent != null } ?: return@forEach
        val reference = "miui.focus.action_musicone_$key"
        bindings.putParcelable(reference, action)
        links.put(JSONObject().put("type", 0).put("action", reference).put("clickWithCollapse", false))
    }
    if (links.length() > 0) parameters.put("actions", links)
    return Bundle(extras).apply {
        putString(HYPER_OS_STANDARD_PARAM, root.toString())
        putBundle("miui.focus.actions", bindings)
    }
}
