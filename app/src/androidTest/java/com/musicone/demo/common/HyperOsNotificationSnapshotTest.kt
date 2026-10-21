package com.musicone.demo

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Android 通知检查对象复用，避免纯 JVM 的不可变替身掩盖丢帧。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
@androidx.annotation.OptIn(UnstableApi::class)
class HyperOsNotificationSnapshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun independentProbeDoesNotCarryMediaStyleSessionOrCustomViews() {
        val notification = HyperOsIslandProbe.notification(context)
        assertNull(notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertFalse(notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertFalse(notification.extras.containsKey(HYPER_OS_CUSTOM_PARAM))
        HYPER_OS_CARD_LAYOUT_KEYS.forEach { assertFalse(notification.extras.containsKey(it)) }
        assertEquals(60_000L, notification.timeoutAfter)
        val params = org.json.JSONObject(notification.extras.getString(HYPER_OS_STANDARD_PARAM)!!)
            .getJSONObject("param_v2")
        assertEquals("超级岛测试", params.getJSONObject("baseInfo").getString("title"))
        assertEquals(60, params.getJSONObject("param_island").getInt("islandTimeout"))
    }

    private fun base() = MediaNotification(101, Notification.Builder(context, "base")
        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("歌名").build())
    private fun overlay(lyric: String, sequence: Long) = Bundle().apply {
        putString(HYPER_OS_CUSTOM_PARAM, hyperOsIslandPayload(HyperOsIslandSummary("歌名", "", lyric), sequence, false))
        putString(HYPER_OS_TICKER, lyric)
    }

    @Test fun switchingTemplatesRemovesEveryKeyFromTheOtherProtocolWithoutMutatingPreviousFrames() {
        val customExtras = overlay("原文", 1).apply {
            HYPER_OS_CARD_LAYOUT_KEYS.forEach { putParcelable(it, RemoteViews(context.packageName, R.layout.hyper_os_island_card)) }
        }
        val custom = hyperOsDecoratedNotification(context, base(), customExtras, "island")
        val frame = HyperOsIslandFrame(SystemPlaybackKey("track", 1), HyperOsIslandSummary("歌名", "", "原文二"),
            "完整歌名", "歌手", 2, 100)
        val standardExtras = Bundle().apply {
            putString(HYPER_OS_STANDARD_PARAM, hyperOsStandardIslandPayload(frame, 2, false))
            putString(HYPER_OS_TICKER, "原文二")
        }
        val standard = hyperOsDecoratedNotification(context, custom, standardExtras, "island")
        assertTrue(standard.notification.extras.containsKey(HYPER_OS_STANDARD_PARAM))
        assertFalse(standard.notification.extras.containsKey(HYPER_OS_CUSTOM_PARAM))
        HYPER_OS_CARD_LAYOUT_KEYS.forEach { assertFalse(standard.notification.extras.containsKey(it)) }
        assertEquals("原文二", standard.notification.extras.getString(HYPER_OS_TICKER))
        assertTrue(custom.notification.extras.containsKey(HYPER_OS_CUSTOM_PARAM))
        assertFalse(custom.notification.extras.containsKey(HYPER_OS_STANDARD_PARAM))
        val back = hyperOsDecoratedNotification(context, standard, customExtras, "island")
        assertFalse(back.notification.extras.containsKey(HYPER_OS_STANDARD_PARAM))
        assertTrue(back.notification.extras.containsKey(HYPER_OS_CUSTOM_PARAM))
        assertFalse(back.notification.extras.containsKey("miui.focus.actions"))
        HYPER_OS_CARD_LAYOUT_KEYS.forEach { assertTrue(back.notification.extras.containsKey(it)) }
    }

    @Test fun enablingLyricsCreatesItsCategoryBeforeAnyPlaybackOrArtworkIsAvailable() {
        HyperOsLyricsOptions.initialize(context)
        val enabled = HyperOsLyricsOptions.enabled.value
        try {
            HyperOsLyricsOptions.setEnabled(true)
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(HYPER_OS_CHANNEL_ID)
            assertNotNull(channel)
            assertEquals("超级岛歌词", channel.name.toString())
        } finally { HyperOsLyricsOptions.setEnabled(enabled) }
    }

    @Test fun decoratingTheNextLyricCannotMutateTheBaseOrPreviousNotification() {
        val base = base()
        val firstExtras = overlay("第一句", 1)
        val first = hyperOsDecoratedNotification(context, base, firstExtras, "island")
        val second = hyperOsDecoratedNotification(context, base, overlay("第二句", 2), "island")
        assertNotSame(base.notification, first.notification)
        assertNotSame(first.notification, second.notification)
        assertEquals("base", base.notification.channelId)
        assertFalse(base.notification.extras.containsKey(HYPER_OS_CUSTOM_PARAM))
        assertEquals(firstExtras.getString(HYPER_OS_CUSTOM_PARAM), first.notification.extras.getString(HYPER_OS_CUSTOM_PARAM))
        assertNotEquals(first.notification.extras.getString(HYPER_OS_CUSTOM_PARAM),
            second.notification.extras.getString(HYPER_OS_CUSTOM_PARAM))
    }

    @Test fun successiveLyricsReachTheHandoffCallbackWithOneReusedMediaNotification() {
        val pending = mutableListOf<Runnable>()
        val published = mutableListOf<MediaNotification>()
        val handoff = HyperOsNotificationHandoff<MediaNotification, Bundle>(
            enqueue = { pending += it }, remove = { pending -= it },
            decorate = { media, extras -> hyperOsDecoratedNotification(context, media, extras, "island") },
            equivalent = { a, b -> a.notification.extras.getString(HYPER_OS_CUSTOM_PARAM) ==
                b.notification.extras.getString(HYPER_OS_CUSTOM_PARAM) },
            publish = { published += it },
        )
        handoff.mediaCreated("track", base())
        for ((index, lyric) in listOf("第一句", "第二句", "第三句").withIndex()) {
            handoff.overlayUpdated("track", overlay(lyric, index + 1L))
            val tasks = pending.toList()
            pending.clear()
            tasks.forEach(Runnable::run)
            assertEquals(index + 1, published.size)
        }
        assertTrue(published.first().notification.extras.getString(HYPER_OS_CUSTOM_PARAM).orEmpty().contains("第一句"))
        handoff.close()
    }

    @Test fun changingTitlesDoesNotMoveControlsOrClipTheProgressRow() {
        val card = LayoutInflater.from(context).inflate(R.layout.hyper_os_island_card, null)
        val title = card.findViewById<TextView>(R.id.island_title)
        card.findViewById<TextView>(R.id.island_artist).text = "歌手"
        card.findViewById<TextView>(R.id.island_elapsed).text = "00:01"
        val width = (320 * context.resources.displayMetrics.density).toInt()
        fun measure() {
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        }
        title.text = "歌"
        measure()
        val height = card.measuredHeight
        val controlsTop = (card.findViewById<View>(R.id.island_play_pause).parent as View).top
        title.text = "很长的歌名与歌曲资料".repeat(10)
        measure()
        assertEquals(height, card.measuredHeight)
        assertEquals(controlsTop, (card.findViewById<View>(R.id.island_play_pause).parent as View).top)
        val elapsed = card.findViewById<View>(R.id.island_elapsed)
        assertTrue((elapsed.parent as View).bottom <= card.measuredHeight)
    }
}
