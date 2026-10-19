package com.musicone.demo

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsbOutputRecoveryTest {
    @Test fun usbTransportFailuresAreRecognizedBeforeAndAfterTheSettingChanges() {
        val error = RuntimeException(UsbOutputException(IOException("设备已断开")))
        assertTrue(isUsbOutputFailure(error, true))
        assertTrue(isUsbOutputFailure(error, false))
        assertFalse(isUsbOutputFailure(IOException("网络不可用"), true, directRouteActive = true))
        val write = AudioSink.WriteException(-1, Format.Builder().build(), false)
        assertTrue(isUsbOutputFailure(RuntimeException(write), true, directRouteActive = true))
        assertFalse(isUsbOutputFailure(write, false, directRouteActive = true))
    }

    @Test fun ordinaryOutputFailuresAreNotUsbLossBeforeTheDirectRouteTakesOver() {
        val format = Format.Builder().build()
        val write = AudioSink.WriteException(-1, format, false)
        val init = AudioSink.InitializationException("系统输出初始化失败", -1, format, false,
            IOException("没有可用音频设备"))
        assertFalse(isUsbOutputFailure(write, true, directRouteActive = false))
        assertFalse(isUsbOutputFailure(init, true, directRouteActive = false))
        // 直通链路真正接管后，同类错误仍按 USB 中断处理。
        assertTrue(isUsbOutputFailure(init, true, directRouteActive = true))
    }

    @Test fun unplugKeepsTheSameTrackPositionAndPausedIntent() = runBlocking {
        val fake = RoutePlayer()
        fake.playWhenReady = false
        reprepareUsbRoute(fake.player) { }
        assertEquals(listOf("stop", "seek:42000", "prepare"), fake.calls)
        assertEquals("one", fake.item.mediaId)
        assertFalse(fake.playWhenReady)
    }

    @Test fun pauseDuringResetIsNeverOverwrittenByTheOldPlayingIntent() = runBlocking {
        val fake = RoutePlayer()
        reprepareUsbRoute(fake.player) { fake.playWhenReady = false }
        assertFalse(fake.playWhenReady)
        assertEquals(1, fake.calls.count { it == "prepare" })
    }

    @Test fun changingTrackDuringResetDoesNotSeekOrPrepareTheNewTrack() = runBlocking {
        val fake = RoutePlayer()
        reprepareUsbRoute(fake.player) { fake.item = MediaItem.Builder().setMediaId("two").build() }
        assertEquals(listOf("stop"), fake.calls)
    }

    @Test fun idleErrorCanBePreparedAgainWithoutWaitingForAnInactiveRenderer() = runBlocking {
        val fake = RoutePlayer()
        fake.state = Player.STATE_IDLE
        var routeSettled = false
        reprepareUsbRoute(fake.player, awaitSystemRoute = { routeSettled = true }) {
            fail("空闲渲染器不应等待清理回调")
        }
        assertTrue(routeSettled)
        assertEquals(listOf("stop", "seek:42000", "prepare"), fake.calls)
    }

    @Test fun failedResetDoesNotPrepareOverUnreleasedOutput() = runBlocking {
        val fake = RoutePlayer()
        try {
            reprepareUsbRoute(fake.player) { error("USB 输出重置超时") }
            fail("应停止恢复")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("stop"), fake.calls)
    }

    @Test fun disconnectedRequestsAreReleasedOnlyAfterClosingTheConnection() {
        val calls = mutableListOf<String>()
        releaseUsbStream({ calls += "reap"; error("设备已断开") },
            { calls += "close" }, { calls += "free" })
        assertEquals(listOf("reap", "close", "free"), calls)
    }

    @Test fun failedConnectionCloseNeverFreesPossiblyReferencedNativeBuffers() {
        var freed = false
        try {
            releaseUsbStream({ error("回收失败") }, { error("关闭失败") }, { freed = true })
            fail("应保留关闭失败")
        } catch (_: IllegalStateException) { }
        assertFalse(freed)
    }

    @Test fun normalReleaseClosesOnceWithoutForcingNativeRelease() {
        val calls = mutableListOf<String>()
        releaseUsbStream({ calls += "reap-and-free" }, { calls += "close" }, { fail("无需强制释放") })
        assertEquals(listOf("reap-and-free", "close"), calls)
    }

    private class RoutePlayer {
        var item = MediaItem.Builder().setMediaId("one").build()
        var playWhenReady = true
        var state = Player.STATE_READY
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> 42_000L
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "stop" -> { calls += "stop"; null }
                "seekTo" -> { calls += "seek:${args!![0]}"; null }
                "prepare" -> { calls += "prepare"; null }
                else -> null
            }
        } as Player
    }
}
