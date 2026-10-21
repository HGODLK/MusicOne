package com.musicone.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi

/** 歌词仅装饰同一条媒体通知，不另建通知、通道或前台服务。 */
@RequiresApi(26)
internal class HyperOsIslandPublisher(
    private val context: Context,
    private val notifications: HyperOsMediaNotificationProvider,
) {
    private var previous: PublishedIsland? = null
    private var iconBitmap: Bitmap? = null
    private var bitmapIcon: Icon? = null
    private var sequence = 0L
    private val card = HyperOsIslandCard(context)
    private val placeholder by lazy { Icon.createWithResource(context, R.drawable.ic_island_album_placeholder) }

    fun publish(cover: Bitmap?, frame: HyperOsIslandFrame, standard: Boolean) {
        val publication = PublishedIsland(cover, frame, standard)
        // Media3 负责前台生命周期；同一帧不重复发布，也不使用超时通知续期。
        if (publication == previous) return
        if (!HyperOsIslandAccess.notificationsAllowed(context)) {
            clear()
            HyperOsIslandDiagnostics.waiting("通知权限未开启")
            return
        }
        try {
            if (cover !== iconBitmap) {
                iconBitmap = cover
                bitmapIcon = cover?.let(Icon::createWithBitmap)
            }
            val coverIcon = bitmapIcon ?: placeholder
            val extras = Bundle().apply {
                val next = ++sequence
                val promote = previous?.frame?.key != frame.key
                if (standard) putString(HYPER_OS_STANDARD_PARAM, hyperOsStandardIslandPayload(frame, next, promote))
                else putString(HYPER_OS_CUSTOM_PARAM, hyperOsIslandPayload(frame.summary, next, promote))
                putString(HYPER_OS_TICKER, frame.summary.lyric)
                putBundle("miui.focus.pics", Bundle().apply { putParcelable(HYPER_OS_COVER_KEY, coverIcon) })
                if (!standard) {
                    val views = card.render(frame, cover)
                    putInt(HYPER_OS_FOREGROUND_KEY, card.foreground)
                    HYPER_OS_CARD_LAYOUT_KEYS.forEach { putParcelable(it, views) }
                }
            }
            notifications.update(extras, frame.key.trackId)
            previous = publication
        } catch (error: RuntimeException) {
            Log.w("MusicOneHyperOs", "超级岛通知发布失败：${error.javaClass.simpleName}")
            clear()
            HyperOsIslandDiagnostics.failed("构建参数或卡片失败（${error.javaClass.simpleName}）")
        }
    }

    fun clear() {
        if (previous == null) return
        previous = null
        iconBitmap = null; bitmapIcon = null
        card.clear()
        notifications.update(null)
    }

    private data class PublishedIsland(val cover: Bitmap?, val frame: HyperOsIslandFrame, val standard: Boolean)
}
