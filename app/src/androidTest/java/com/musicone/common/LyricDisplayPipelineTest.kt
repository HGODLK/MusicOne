package com.musicone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只运行于独立实验包；检查真实 Compose、原玻璃缓存和最终 GPU 字形，不保存截图。 */
@RunWith(AndroidJUnit4::class)
class LyricDisplayPipelineTest {
    @Test fun hiddenTargetCanAcknowledgeAndRepeatedPreparationStillProducesVisibleGlyphs() {
        assumeTrue(BuildConfig.BUILD_TYPE == "sourceDebug" || BuildConfig.BUILD_TYPE == "debugRelease")
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent().setClassName(instrumentation.targetContext.packageName,
            "com.musicone.validation.LyricDisplayValidationActivity")
        val ticket = mutableStateOf(Any())
        val visible = mutableStateOf(false)
        val width = mutableStateOf(300)
        val mounted = mutableStateOf(true)
        val drawn = LinkedBlockingQueue<Any>()
        val retained = PlayerLyricsContent()
        val readiness = LyricLayoutReadiness()
        val track = LyricPresentation("fixture", MusicSource.QQ, 10_000,
            listOf(TimedLyric(0, "Visible lyric"), TimedLyric(5_000, "Next lyric")))
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            lateinit var view: ComposeView
            scenario.onActivity { activity ->
                view = ComposeView(activity)
                activity.setContentView(view)
                view.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalPlayerLyricsContent provides retained,
                            LocalContentColor provides Color.White) {
                            Box(Modifier.fillMaxSize().background(Color.Black)) {
                                if (mounted.value) {
                                    val request = ticket.value
                                    Box(Modifier.size(width.value.dp, 400.dp).graphicsLayer {
                                        alpha = if (visible.value) 1f else 0f
                                    }) {
                                        ImmersiveLyrics(track, { 0L }, {},
                                            Modifier.fillMaxSize().phoneLyricsViewport(PhoneLyricsViewport()),
                                            active = true, followPlayback = false, prepareWhileHidden = true,
                                            layoutReadiness = readiness, displayRequest = request,
                                            typography = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, width.value.toFloat()),
                                            onReady = { drawn.offer(request); Unit })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            repeat(8) { index ->
                if (index % 3 == 2) {
                    instrumentation.runOnMainSync { mounted.value = false }
                    nextFrames(view)
                    instrumentation.runOnMainSync { mounted.value = true }
                }
                val request = Any()
                instrumentation.runOnMainSync {
                    visible.value = false
                    width.value = if (index % 2 == 0) 300 else 280
                    ticket.value = request
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var acknowledged = false
                while (System.nanoTime() < deadline && !acknowledged)
                    acknowledged = drawn.poll(100, TimeUnit.MILLISECONDS) === request
                assertTrue("零权重目标必须取得本次实际字形回执", acknowledged)
                instrumentation.runOnMainSync { visible.value = true }
                nextFrames(view)
                lateinit var activity: Activity
                scenario.onActivity { activity = it }
                assertGlyphs(activity, view)
            }
        }
    }

    private fun nextFrames(view: ComposeView) {
        val ready = CountDownLatch(1)
        view.post { view.postOnAnimation { view.postOnAnimation { ready.countDown() } } }
        assertTrue(ready.await(3, TimeUnit.SECONDS))
    }

    private fun assertGlyphs(activity: Activity, view: ComposeView) {
        // PixelCopy 异步回执由后台线程接收，不阻塞主线程等待绘制。
        lateinit var bitmap: Bitmap
        val ready = CountDownLatch(1)
        var result = -1
        val thread = android.os.HandlerThread("LyricDisplayCopy").apply { start() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val origin = IntArray(2).also(view::getLocationInWindow)
            PixelCopy.request(activity.window,
                Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height), bitmap,
                { code -> result = code; ready.countDown() }, Handler(thread.looper))
        }
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            var glyphs = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) > 150 &&
                    android.graphics.Color.blue(color) > 150) glyphs++
            }
            assertTrue("实际合成结果不能只有背景而没有歌词", glyphs > 10)
        } finally { bitmap.recycle(); thread.quitSafely() }
    }
}
