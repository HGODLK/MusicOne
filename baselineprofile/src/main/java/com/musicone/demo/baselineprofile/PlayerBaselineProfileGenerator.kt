package com.musicone.demo.baselineprofile

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只重新采集播放器及歌词动效，避免触碰其他页面已经保存的 Profile。 */
@RunWith(AndroidJUnit4::class)
class PlayerBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun capturePlayerBaselineProfile() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            maxIterations = 1,
            stableIterations = 1,
            strictStability = false,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            markDevice()
            if (isManualCapture()) {
                mark("播放器人工采集窗口", true, "等待模拟器实机操作")
                SystemClock.sleep(180_000)
                return@collect
            }
            if (!waitForHome()) {
                mark("播放器专用采集", false, "首页未出现播放页入口")
                return@collect
            }
            capturePlayerPageEntryAndExit()
        }
    }

    private fun MacrobenchmarkScope.capturePlayerPageEntryAndExit() {
        if (!clickDescription("打开播放页", 10_000)) {
            mark("打开播放页", false)
            return
        }
        val opened = device.wait(Until.hasObject(By.desc("播放列表")), 10_000)
        mark("打开播放页", opened)
        if (!opened) return

        captureLyricToggleAnimation()
        captureTrackSwitchLyricAnimation()
        captureNormalLyricPlayback()

        device.pressBack()
        device.waitForIdle()
        mark("关闭播放页", device.wait(Until.hasObject(By.desc("打开播放页")), 5_000))
    }

    private fun MacrobenchmarkScope.captureLyricToggleAnimation() {
        if (isTablet()) {
            mark("平板歌词打开关闭动画", device.hasObject(By.desc("播放列表")), "歌词在双栏播放页常驻")
            return
        }
        val controlReady = device.wait(Until.hasObject(By.desc("开启歌词")), 5_000) ||
            device.hasObject(By.desc("关闭歌词"))
        if (!controlReady) {
            mark("歌词打开动画", false, "未出现歌词开关")
            mark("歌词关闭动画", false, "未出现歌词开关")
            return
        }
        if (device.hasObject(By.desc("关闭歌词"))) {
            clickDescription("关闭歌词", 4_000)
            device.wait(Until.hasObject(By.desc("开启歌词")), 4_000)
        }
        val opened = clickDescription("开启歌词", 4_000) &&
            device.wait(Until.hasObject(By.desc("关闭歌词")), 5_000)
        mark("歌词打开动画", opened)
        val closed = opened && clickDescription("关闭歌词", 4_000) &&
            device.wait(Until.hasObject(By.desc("开启歌词")), 5_000)
        mark("歌词关闭动画", closed)
    }

    private fun MacrobenchmarkScope.captureTrackSwitchLyricAnimation() {
        val lyricsVisible = if (isTablet()) {
            device.hasObject(By.desc("播放列表"))
        } else {
            if (!device.hasObject(By.desc("关闭歌词"))) clickDescription("开启歌词", 4_000)
            device.wait(Until.hasObject(By.desc("关闭歌词")), 4_000)
        }
        if (!lyricsVisible) {
            mark("上一首歌词动画", false, "歌词窗口未打开")
            mark("下一首歌词动画", false, "歌词窗口未打开")
            return
        }
        val next = clickDescription("下一首", 5_000)
        device.waitForIdle()
        SystemClock.sleep(1_200)
        mark("下一首歌词动画", next)
        val previous = clickDescription("上一首", 5_000)
        device.waitForIdle()
        SystemClock.sleep(1_200)
        mark("上一首歌词动画", previous)
    }

    private fun MacrobenchmarkScope.captureNormalLyricPlayback() {
        val lyricsVisible = if (isTablet()) {
            device.hasObject(By.desc("播放列表"))
        } else {
            device.hasObject(By.desc("关闭歌词")) || clickDescription("开启歌词", 4_000)
        }
        if (!lyricsVisible) {
            mark("正常播放歌词动画", false, "歌词窗口未打开")
            return
        }
        if (device.hasObject(By.desc("播放"))) clickDescription("播放", 3_000)
        device.waitForIdle()
        SystemClock.sleep(3_000)
        mark("正常播放歌词动画", true)
    }

    private fun MacrobenchmarkScope.waitForHome(): Boolean =
        device.wait(Until.hasObject(By.text("每日推荐")), 20_000) &&
            device.hasObject(By.desc("打开播放页"))

    private fun MacrobenchmarkScope.clickDescription(description: String, timeoutMs: Long): Boolean =
        clickNode(By.desc(description), timeoutMs)

    private fun MacrobenchmarkScope.clickNode(selector: BySelector, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val target = runCatching {
                device.findObjects(selector)
                    .asSequence()
                    .filter { node -> runCatching { node.isEnabled && node.visibleBounds.width() > 0 && node.visibleBounds.height() > 0 }.getOrDefault(false) }
                    .sortedByDescending { node -> runCatching { node.visibleBounds.width() * node.visibleBounds.height() }.getOrDefault(0) }
                    .firstNotNullOfOrNull { node ->
                    runCatching {
                        var candidate = node
                        while (!candidate.isClickable && candidate.parent != null) candidate = candidate.parent
                        candidate.takeIf { it.isClickable }
                    }.getOrNull()
                    }
            }.getOrNull()
            if (target != null && runCatching {
                    val bounds = target.visibleBounds
                    device.click(bounds.centerX(), bounds.centerY())
                    device.waitForIdle()
                    true
                }.getOrDefault(false)
            ) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun MacrobenchmarkScope.isTablet(): Boolean = device.displayWidth >= 1600

    private fun isManualCapture(): Boolean =
        InstrumentationRegistry.getArguments().getString("manual_capture") == "true"

    private fun MacrobenchmarkScope.markDevice() {
        Log.i(PROFILE_LOG, "设备=$DEVICE_LABEL 型号=${Build.MODEL} 设备代号=${Build.DEVICE} 分辨率=${device.displayWidth}x${device.displayHeight} 包=$PACKAGE_NAME 音源=QQ")
    }

    private fun mark(page: String, captured: Boolean, detail: String = "") {
        val suffix = if (detail.isBlank()) "" else " [$detail]"
        Log.i(PROFILE_LOG, "设备=$DEVICE_LABEL 包=$PACKAGE_NAME 音源=QQ 页面=$page=${if (captured) "已采集" else "未采集"}$suffix")
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.shuyunr.musicone"
        const val PROFILE_LOG = "MusicOneProfile"
        val DEVICE_LABEL = if (Build.MODEL.contains("SM-S9110", ignoreCase = true)) "平板" else "手机"
    }
}
