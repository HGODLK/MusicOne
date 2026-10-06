package com.musicone.demo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.os.SystemClock
import android.util.Log
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 仅采集冷启动到首页首帧，供 R8 优化主 DEX 布局。 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun captureStartupProfile() {
        baselineProfileRule.collect(
            packageName = "io.github.shuyunr.musicone",
            maxIterations = 1,
            stableIterations = 1,
            strictStability = false,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.desc("首页")), 8_000)
            if (!device.hasObject(By.text("每日推荐"))) device.findObject(By.desc("首页"))?.click()
            // QQ 首页数据可见后，继续覆盖启动遮罩最多半秒的播放页预热和退场。
            val homeReady = device.wait(Until.hasObject(By.text("每日推荐")), 20_000) &&
                device.wait(Until.hasObject(By.desc("打开搜索")), 5_000)
            if (homeReady) SystemClock.sleep(1_000)
            device.waitForIdle()
            Log.i("MusicOneProfile", "入口=QQ启动与播放页预热=${if (homeReady) "已采集" else "未采集"}")
        }
    }
}
