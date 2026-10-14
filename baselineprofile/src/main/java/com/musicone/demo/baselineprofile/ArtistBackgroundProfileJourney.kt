package com.musicone.demo.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

/** 定向复用采集器自身的自动化连接，避免外部 dump 与录制连接竞争或读取旧 XML。 */
internal class ArtistBackgroundProfileJourney(private val device: UiDevice) {
    private val output = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null),
        "artist-background-${System.currentTimeMillis()}").apply { mkdirs() }

    fun capture() {
        Log.i(TAG, "定向证据目录=${output.absolutePath}")
        try {
            snapshot("00-initial")
            returnToRoot()
            click(By.desc("打开搜索"))
            click(By.desc("搜索歌曲、歌手、专辑或歌单"))
            device.executeShellCommand("input text RADWIMPS")
            device.pressEnter()
            check(device.wait(Until.hasObject(By.text("歌手")), 12_000)) { "搜索分类未出现" }
            val tab = device.findObjects(By.text("歌手")).map { it.visibleBounds }
                .filter { it.top < device.displayHeight / 3 }.minByOrNull { it.top }
                ?: error("未找到顶部歌手分类")
            device.click(tab.centerX(), tab.centerY())
            click(By.desc(Pattern.compile("^打开歌手：.+")), 12_000)
            waitArtist()
            snapshot("01-artist")

            click(By.desc("打开播放页"))
            waitPlayer()
            snapshot("02-player-from-artist")
            device.pressBack()
            waitArtist()
            snapshot("03-return-artist")
            mark("歌手页播放控件展开返回")

            // 手势坐标来自当前真实控件边界和当前显示尺寸，兼容手机与平板。
            val mini = device.findObject(By.desc("打开播放页"))?.visibleBounds ?: error("迷你控件未出现")
            device.swipe(mini.centerX(), mini.centerY(), mini.centerX(), device.displayHeight / 3, 25)
            waitPlayer()
            snapshot("04-swipe-open")
            val x = if (device.displayWidth > device.displayHeight) device.displayWidth / 4 else device.displayWidth / 2
            device.swipe(x, device.displayHeight / 5, x, device.displayHeight * 4 / 5, 25)
            waitArtist()
            snapshot("05-swipe-return")
            mark("歌手页播放控件手势开合")

            click(By.desc("打开播放页"))
            waitPlayer()
            if (!device.hasObject(By.desc("暂停"))) click(By.desc("播放"))
            waitPlaying()
            val beforeNext = session("06-before-next")
            click(By.desc("下一首"))
            check(waitMetadataChange(beforeNext)) { "下一首未实际切换" }
            waitPlaying()
            snapshot("07-next-playing")
            mark("下一首解析与实际播放")

            device.pressHome()
            val backgroundBefore = session("08-background-before")
            val positionBefore = effectivePosition(backgroundBefore)
            SystemClock.sleep(6_000)
            val backgroundAfter = session("09-background-after")
            val positionAfter = effectivePosition(backgroundAfter)
            check(positionBefore >= 0 && positionAfter > positionBefore + 3_000) { "后台进度未持续推进" }
            mark("后台播放进度推进", "进度毫秒=$positionBefore->$positionAfter")
            device.executeShellCommand("am start -f 0x30000000 -n io.github.shuyunr.musicone/com.musicone.demo.MainActivity")
            waitPlayer()
            snapshot("10-background-return")

            val progress = device.findObject(By.desc("播放进度"))?.visibleBounds ?: error("进度条未出现")
            val beforeEnd = session("11-before-end")
            val timeLabels = device.findObjects(By.text(Pattern.compile("^[−-]?\\d+:\\d+$")))
                .filter { it.visibleBounds.top > progress.bottom }.map { it.text.orEmpty().trimStart('−', '-') }
            val duration = timeLabels.sumOf { label ->
                val parts = label.split(':')
                parts[0].toLong() * 60_000 + parts[1].toLong() * 1_000
            }
            check(duration > 0) { "歌曲时长未出现" }
            // 滑块两端含触摸留白；先完成真实拖动并核验 seek，再切后台等待自然曲末。
            val endX = progress.left + (progress.width() * .96f).toInt()
            device.swipe(progress.centerX(), progress.centerY(), endX, progress.centerY(), 30)
            SystemClock.sleep(1_200)
            val sought = session("11b-seek-applied")
            check(metadata(sought) == metadata(beforeEnd) && effectivePosition(sought) >= duration * .9) {
                "曲末 seek 未实际生效"
            }
            snapshot("11b-after-seek")
            device.pressHome()
            check(waitMetadataChange(beforeEnd, 20_000)) { "后台曲末未自然进入下一首" }
            val endedNext = session("12-background-auto-next")
            check(playing(endedNext)) { "后台下一首尚未实际播放" }
            mark("后台自然曲末切歌")
            device.executeShellCommand("am start -f 0x30000000 -n io.github.shuyunr.musicone/com.musicone.demo.MainActivity")
            waitPlayer()
            snapshot("13-auto-next-return")
            mark("歌手后台定向覆盖完成")
        } catch (error: Exception) {
            Log.e(TAG, "入口=歌手后台定向覆盖=未采集 原因=${error::class.java.simpleName}:${error.message}", error)
            snapshot("failure")
        }
    }

    private fun click(selector: BySelector, timeout: Long = 8_000) {
        // 复用全量采集器的节点重定位策略，入场重绘期间失效的节点不能作为点击坐标。
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val bounds = runCatching {
                device.findObjects(selector).mapNotNull { runCatching { it.visibleBounds }.getOrNull() }
                    .filter { !it.isEmpty }.maxByOrNull { it.width() * it.height() }
            }.getOrNull()
            if (bounds != null && device.click(bounds.centerX(), bounds.centerY())) {
                SystemClock.sleep(700)
                return
            }
            SystemClock.sleep(100)
        }
        error("控件未出现或边界失效：$selector")
    }

    private fun returnToRoot() {
        // 冷启动遮罩和保留页面可能仍在交接，先等实际页面，不用连续返回键猜测导航状态。
        device.executeShellCommand("am start -W -f 0x30000000 -n io.github.shuyunr.musicone/com.musicone.demo.MainActivity")
        SystemClock.sleep(1_500)
        repeat(7) {
            val nested = device.hasObject(By.desc("播放列表")) ||
                device.hasObject(By.desc("返回")) || device.hasObject(By.desc("返回首页")) ||
                device.hasObject(By.desc("搜索歌曲、歌手、专辑或歌单"))
            if (!nested && device.wait(Until.hasObject(By.desc("打开搜索")), 1_000)) return
            if (nested) {
                device.pressBack()
            }
            SystemClock.sleep(1_000)
        }
        error("无法回到根页面")
    }

    private fun waitArtist() {
        check(device.wait(Until.hasObject(By.desc("播放歌手歌曲")), 10_000)) { "歌手页未真实到达" }
        SystemClock.sleep(700)
    }

    private fun waitPlayer() {
        check(device.wait(Until.hasObject(By.desc("播放列表")), 8_000)) { "播放页未真实到达" }
        SystemClock.sleep(700)
    }

    private fun waitPlaying() {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (playing(session("playing-check"))) return
            SystemClock.sleep(300)
        }
        error("未实际起播")
    }

    private fun session(name: String): String {
        val dump = device.executeShellCommand("dumpsys media_session")
        File(output, "$name-session.txt").writeText(dump)
        val index = dump.indexOf("package=io.github.shuyunr.musicone")
        check(index >= 0) { "MusicOne 媒体会话未出现" }
        return dump.substring(index).substringBefore("\n  Session #")
    }

    // Android 15 把状态打印为 PLAYING(3)，旧系统打印数字；两者都必须是真正播放态。
    private fun playing(value: String) = Regex("state=(?:3(?:,|\\s)|PLAYING\\(3\\))").containsMatchIn(value)
    private fun metadata(value: String) = value.lineSequence().firstOrNull { "metadata:" in it }.orEmpty()
    private fun waitMetadataChange(before: String, timeout: Long = 12_000): Boolean {
        val old = metadata(before)
        check(old.isNotBlank()) { "原曲元数据缺失" }
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = session("metadata-check")
            if (metadata(current).isNotBlank() && metadata(current) != old && playing(current)) return true
            SystemClock.sleep(400)
        }
        return false
    }

    private fun effectivePosition(value: String): Long {
        if (!playing(value)) return -1
        val state = value.lineSequence().firstOrNull { "PlaybackState" in it } ?: return -1
        val position = Regex("position=(\\d+)").find(state)?.groupValues?.get(1)?.toLong() ?: return -1
        val updated = Regex("updated=(\\d+)").find(state)?.groupValues?.get(1)?.toLong() ?: return -1
        val speed = Regex("speed=([\\d.]+)").find(state)?.groupValues?.get(1)?.toDouble() ?: return -1
        return position + ((SystemClock.elapsedRealtime() - updated) * speed).toLong()
    }

    private fun snapshot(name: String) {
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        check(device.takeScreenshot(File(output, "$name.png"))) { "截图未保存" }
    }

    private fun mark(name: String, detail: String = "") = Log.i(TAG, "入口=$name=已采集 $detail")
    private companion object { const val TAG = "MusicOneProfile" }
}
