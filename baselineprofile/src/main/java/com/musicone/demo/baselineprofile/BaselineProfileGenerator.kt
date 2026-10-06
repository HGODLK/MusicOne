package com.musicone.demo.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.BySelector
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * 只对正式包的 QQ 音乐路径采集，页面标记必须在对应页面真实出现后才写入。
 *
 * 手机和平板分别运行本测试；平板的双栏播放页没有手机式歌词开关，单独记录歌词常驻和控件下滑结果。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun captureBaselineProfile() {
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
            returnToRoot()
            val qqHome = waitForQqHome()
            mark("QQ首页真实到达", qqHome)
            if (qqHome) {
                captureHomeFeedAppend()
                capturePlayerPages()
                captureSearchAndEntityPages()
                captureLibraryAndPlaylistPages()
                captureSettingsPages()
                returnToRoot()
                if (!device.hasObject(By.desc("打开搜索"))) restartAtHome()
                mark("QQ路径采集结束", device.hasObject(By.desc("打开搜索")))
            }
        }
    }

    private fun MacrobenchmarkScope.captureHomeFeedAppend() {
        returnToRoot()
        goHome()
        if (!device.hasObject(By.text("每日推荐"))) {
            mark("首页信息流", false)
            return
        }
        repeat(2) { index ->
            val before = visibleTextCount()
            val scrolled = scrollHomeFeed()
            device.waitForIdle()
            val settled = device.wait(Until.hasObject(By.desc("打开播放页")), 2_000)
            val after = visibleTextCount()
            mark(
                "首页信息流追加加载${index + 1}",
                scrolled && settled,
                "滚动=${scrolled} 文本节点=${before}->${after}",
            )
        }
    }

    private fun MacrobenchmarkScope.capturePlayerPages() {
        returnToRoot()
        goHome()
        if (!clickDescription("打开播放页")) {
            mark("播放页", false)
            return
        }
        val playerReached = device.wait(Until.hasObject(By.desc("播放列表")), 8_000)
        mark("播放页", playerReached)
        if (!playerReached) return

        if (isTablet()) mark("平板歌词常驻", device.hasObject(By.desc("播放列表")))

        val queueClicked = openQueue()
        val queueOpened = device.wait(Until.hasObject(By.text("播放列表")), 4_000)
        mark("播放列表", queueClicked && queueOpened)
        if (queueOpened) {
            val songMenuOpened = clickDescription("更多", 5_000) &&
                device.wait(Until.hasObject(By.text("添加到")), 4_000)
            mark("播放列表歌曲更多", songMenuOpened)
            if (songMenuOpened) device.pressBack()
            scrollAny()
            device.pressBack()
        }
        if (clickDescription(Pattern.compile("^当前.+点击选择音质$"))) device.pressBack()
        clickDescription("下一首")
        device.waitForIdle()
        clickDescription("上一首")
        device.waitForIdle()
        if (isTablet()) captureTabletPlayerSwipe()
        returnToRoot()
    }

    private fun MacrobenchmarkScope.capturePlayerRelatedEntities() {
        if (!clickDescription("查看歌手或专辑")) {
            mark("播放页打开歌手页", false, "未打开歌手/专辑关联菜单")
            mark("播放页打开专辑页", false, "未打开歌手/专辑关联菜单")
            return
        }
        var singerMenu = clickText("查看歌手")
        if (!singerMenu) {
            // 关联菜单可能正在完成入场动画，重新打开后再按语义文本定位。
            clickDescription("查看歌手或专辑", 3_000)
            singerMenu = clickText("查看歌手", 3_000)
        }
        val singerReached = singerMenu && waitEntityPage()
        mark("播放页打开歌手页", singerReached)
        if (singerReached) device.pressBack()
        device.waitForIdle()
        val albumMenuRestored = repeatUntil(2) {
            device.hasObject(By.text("查看专辑")) ||
                (clickDescription("查看歌手或专辑", 5_000) &&
                    device.wait(Until.hasObject(By.text("查看专辑")), 3_000))
        }
        if (!albumMenuRestored) {
            mark("播放页打开专辑页", false, "返回后关联菜单未恢复")
            return
        }
        val albumMenu = clickText("查看专辑")
        val albumReached = albumMenu && waitEntityPage()
        mark("播放页打开专辑页", albumReached)
        if (albumReached) device.pressBack()
    }

    private fun MacrobenchmarkScope.captureTabletPlayerSwipe() {
        // 平板保留右侧歌词和播放控制，使用下一首按钮作为稳定的控件可见证据。
        val before = device.wait(Until.hasObject(By.desc("下一首")), 10_000)
        val width = device.displayWidth
        val height = device.displayHeight
        val x = (width * .78f).toInt()
        device.swipe(x, height / 3, x, height - 160, 24)
        device.waitForIdle()
        val after = device.hasObject(By.desc("下一首"))
        mark("平板播放控件下滑后仍可见", before && after)
    }

    private fun MacrobenchmarkScope.openQueue(): Boolean {
        if (!device.wait(Until.hasObject(By.desc("播放列表")), 20_000)) return false
        if (clickDescription("播放列表", 5_000) &&
            device.wait(Until.hasObject(By.text("播放列表")), 4_000)) return true
        if (clickMatchingCenter(By.desc("播放列表")) &&
            device.wait(Until.hasObject(By.text("播放列表")), 4_000)) return true
        listOf(.87f, .62f, .42f).forEach { fraction ->
            device.click((device.displayWidth * fraction).toInt(), device.displayHeight - 170)
            if (device.wait(Until.hasObject(By.text("播放列表")), 4_000)) return true
        }
        return false
    }

    private fun MacrobenchmarkScope.captureLyricsSequence() {
        if (isTablet()) return
        if (!waitForLyricsControl()) {
            mark("歌词第一次打开", false, "搜索结果播放页未出现歌词控件")
            mark("歌词第一次关闭", false, "搜索结果播放页未出现歌词控件")
            mark("歌词第二次打开（无二次位置调整）", false, "搜索结果播放页未出现歌词控件")
            mark("歌词第二次关闭", false, "搜索结果播放页未出现歌词控件")
            return
        }
        val opened = clickDescription("开启歌词") &&
            device.wait(Until.hasObject(By.desc("关闭歌词")), 5_000)
        mark("歌词第一次打开", opened)
        val closed = opened && clickDescription("关闭歌词") &&
            device.wait(Until.hasObject(By.desc("开启歌词")), 5_000)
        mark("歌词第一次关闭", closed)
        val reopened = closed && clickDescription("开启歌词") &&
            device.wait(Until.hasObject(By.desc("关闭歌词")), 5_000)
        mark("歌词第二次打开（无二次位置调整）", reopened)
        val closedAgain = reopened && clickDescription("关闭歌词") &&
            device.wait(Until.hasObject(By.desc("开启歌词")), 5_000)
        mark("歌词第二次关闭", closedAgain)
    }

    private fun MacrobenchmarkScope.captureLyricPlaybackAnimation() {
        val opened = if (isTablet()) {
            // 平板歌词常驻在播放页右侧，没有手机式开关。
            device.hasObject(By.desc("播放列表"))
        } else {
            if (!device.hasObject(By.desc("关闭歌词"))) clickDescription("开启歌词", 4_000)
            device.wait(Until.hasObject(By.desc("关闭歌词")), 4_000)
        }
        if (!opened) {
            mark("歌词播放逐句动画", false, "歌词窗口未打开")
            return
        }
        val x = if (isTablet()) (device.displayWidth * .74f).toInt() else device.displayWidth / 2
        // 点击当前窗口下方两行，覆盖点击定位和随后播放换句的连续动画。
        device.click(x, (device.displayHeight * .62f).toInt())
        device.waitForIdle()
        device.click(x, (device.displayHeight * .70f).toInt())
        device.waitForIdle()
        SystemClock.sleep(1_200)
        mark("歌词播放逐句动画", true)
        if (!isTablet()) clickDescription("关闭歌词", 3_000)
    }

    private fun MacrobenchmarkScope.captureSearchAndEntityPages() {
        if (!openSearchResults()) {
            mark("搜索", false, "首页未出现打开搜索")
            return
        }
        mark("搜索", true)
        captureSearchPlaybackEntityPages()

        listOf("综合", "单曲", "歌手", "专辑", "歌单").forEach { category ->
            // 每个分类都可能被列表滚动带离屏幕，先回到分类栏再点击，避免平板双栏页面漏采集。
            scrollToTop()
            val categoryReached = clickSearchCategory(category)
            mark("搜索分类详情-$category", categoryReached)
            device.waitForIdle()
            scrollAny()
        }
        val artistOpened = openFreshSearchEntity("歌手", By.desc(Pattern.compile("^打开歌手：.+")))
        mark("歌手页", artistOpened)
        if (artistOpened) {
            device.wait(Until.hasObject(By.text(Pattern.compile("^歌曲 \\d+$"))), 8_000)
            val artistAlbumSection = clickText(Pattern.compile("^专辑 \\d+$")) ||
                clickMatchingCenter(By.text(Pattern.compile("^专辑 \\d+$")))
            mark("歌手页专辑分类", artistAlbumSection)
            scrollAny()
            device.pressBack()
        }
        val albumOpened = openFreshSearchEntity("专辑", By.desc(Pattern.compile("^打开专辑：.+")))
        mark("专辑页", albumOpened)
        if (albumOpened) {
            device.wait(Until.hasObject(By.desc("返回首页")), 8_000)
            scrollAny()
            device.pressBack()
        }
        val playlistOpened = openFreshSearchEntity("歌单", By.desc(Pattern.compile("^打开歌单：.+")))
        mark("搜索歌单页", playlistOpened)
        if (playlistOpened) {
            device.wait(Until.hasObject(By.desc("返回首页")), 8_000)
            scrollAny()
            device.pressBack()
        }
        returnToRoot()
    }

    private fun MacrobenchmarkScope.openSearchResults(): Boolean {
        returnToRoot()
        goHome()
        if (!clickDescription("打开搜索")) return false
        val fieldSelector = By.desc("搜索歌曲、歌手、专辑或歌单")
        if (!device.wait(Until.hasObject(fieldSelector), 5_000)) return false
        val field = device.findObject(fieldSelector) ?: return false
        field.click()
        // Compose 输入框的无障碍节点不是稳定的 EditText，使用真实输入事件避免只改节点快照。
        device.executeShellCommand("input text $PROFILE_QUERY")
        device.pressEnter()
        return device.wait(Until.hasObject(By.text("综合")), 10_000)
    }

    private fun MacrobenchmarkScope.openFreshSearchEntity(category: String, selector: BySelector): Boolean {
        // 搜索结果可能偶发空白；重新进入搜索页再试一次，仍以实体页真实到达为准。
        repeat(2) {
            if (openSearchResults()) {
                scrollToTop()
                if (clickSearchCategory(category) && openSearchEntity(selector)) return true
            }
        }
        return false
    }

    private fun MacrobenchmarkScope.clickSearchCategory(category: String): Boolean {
        // 手机结果行也会出现“歌手”等同名文本，优先选择屏幕最上方的分类栏。
        val bounds = device.findObjects(By.text(category)).mapNotNull { node ->
            runCatching { node.visibleBounds }.getOrNull()
        }.filter { it.top < device.displayHeight / 3 }.minByOrNull { it.top } ?: return false
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
        return true
    }

    private fun MacrobenchmarkScope.captureSearchPlaybackEntityPages() {
        val songClicked = clickDescription(Pattern.compile("^播放歌曲：.+"), 5_000)
        if (!songClicked) {
            mark("播放页打开歌手页", false, "搜索结果没有可播放的 QQ 单曲")
            mark("播放页打开专辑页", false, "搜索结果没有可播放的 QQ 单曲")
            return
        }
        device.waitForIdle()
        if (!clickDescription("打开播放页") ||
            !device.wait(Until.hasObject(By.desc("播放列表")), 8_000)) {
            mark("播放页打开歌手页", false, "搜索单曲未到达播放页")
            mark("播放页打开专辑页", false, "搜索单曲未到达播放页")
            return
        }
        captureLyricsSequence()
        captureLyricPlaybackAnimation()
        capturePlayerRelatedEntities()
        device.pressBack()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.captureLibraryAndPlaylistPages() {
        returnToRoot()
        if (!device.hasObject(By.desc("我的"))) restartAtHome()
        if (!clickDescription("我的")) {
            mark("我的", false)
            return
        }
        mark("我的", true)
        device.wait(Until.hasObject(By.desc("打开设置")), 6_000)
        scrollToTop()
        val favoritesOpened = clickFirstText(listOf("我喜欢的音乐", "我喜欢"), 10_000)
        mark("我喜欢歌单", favoritesOpened)
        if (favoritesOpened) {
            val reached = device.wait(Until.hasObject(By.text("我喜欢的音乐")), 8_000) &&
                (device.hasObject(By.desc("返回首页")) || device.hasObject(By.desc("返回")))
            mark("我喜欢的音乐详情", reached)
            if (reached) capturePlaylistSort("我喜欢排序切换")
            scrollAny()
            clickDescription(Pattern.compile("^播放歌曲：.+"), 5_000)
            device.waitForIdle()
            device.pressBack()
        }
        device.wait(Until.hasObject(By.desc("打开设置")), 5_000)
        scrollToTop()
        val createdTab = clickText(Pattern.compile("^创建的 [1-9]\\d*$"), 5_000)
        val created = createdTab && openFirstLibraryPlaylist()
        mark("自建歌单详情", created, "分区=$createdTab")
        if (created) {
            capturePlaylistSort("自建歌单排序切换")
            device.pressBack()
            device.waitForIdle()
        }
        scrollToTop()
        val collectedTab = clickText(Pattern.compile("^收藏的 [1-9]\\d*$"), 5_000)
        val collected = collectedTab && openFirstLibraryPlaylist()
        mark("收藏歌单详情", collected, "分区=$collectedTab")
        if (collected) {
            scrollAny()
            device.pressBack()
        }
        scrollAny()
    }

    private fun MacrobenchmarkScope.openFirstLibraryPlaylist(): Boolean {
        // 分区切换和列表入场有动效；手机首张卡还可能被底部播放器遮住。
        SystemClock.sleep(400)
        var tab = device.findObject(By.text(Pattern.compile("^(创建的|收藏的) [1-9]\\d*$"))) ?: return false
        if (runCatching { tab.visibleBounds.bottom > device.displayHeight * .6f }.getOrDefault(false)) {
            device.swipe(device.displayWidth / 2, (device.displayHeight * .73f).toInt(),
                device.displayWidth / 2, (device.displayHeight * .31f).toInt(), 28)
            device.waitForIdle()
            SystemClock.sleep(700)
            tab = device.findObject(By.text(Pattern.compile("^(创建的|收藏的) [1-9]\\d*$"))) ?: return false
        }
        val belowTabs = runCatching { tab.visibleBounds.bottom }.getOrNull() ?: return false
        val bounds = device.findObjects(By.clickable(true)).mapNotNull { node ->
            runCatching { node.visibleBounds }.getOrNull()
        }.firstOrNull { bounds ->
            bounds.top >= belowTabs && bounds.bottom < device.displayHeight - 160 &&
                bounds.width() >= 160 && bounds.height() >= 100
        } ?: return false
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
        val reached = waitEntityPage()
        Log.i(PROFILE_LOG, "歌单定位 分区底部=$belowTabs 卡片=$bounds 到达=$reached")
        return reached
    }

    private fun MacrobenchmarkScope.capturePlaylistSort(label: String) {
        val selector = By.desc(Pattern.compile("^当前.+，点击切换为.+$"))
        val original = device.findObject(selector)?.contentDescription
        var changed = false
        var completed = original != null
        repeat(3) { index ->
            if (!completed) return@repeat
            completed = clickNode(selector, 5_000)
            if (completed) {
                device.waitForIdle()
                val current = device.findObject(selector)?.contentDescription
                if (index == 0) changed = current != null && current != original
                if (index == 2) completed = current == original
            }
        }
        mark(label, completed && changed)
    }

    private fun MacrobenchmarkScope.captureSettingsPages() {
        if (!device.hasObject(By.desc("打开设置"))) restartAtHome()
        if (!clickDescription("打开设置")) {
            mark("设置", false)
            return
        }
        mark("设置", true)
        device.wait(Until.hasObject(By.text("设置")), 5_000)
        scrollToTop()
        val sourcesOpened = clickText("主音源")
        mark("主音源", sourcesOpened)
        if (sourcesOpened) {
            device.wait(Until.hasObject(By.text("音乐平台")), 5_000)
            mark("主音源详情", device.hasObject(By.text("音乐平台")))
            scrollAny()
            device.pressBack()
        }
        if (clickText(Pattern.compile("^首选音质 · .+"))) {
            device.waitForIdle()
            device.pressBack()
        }
        // 手机和平板的设置页高度不同，缓存入口可能需要多次滚动才能出现。
        val cacheVisible = scrollUntilText("缓存音乐", 4)
        val cacheOpened = cacheVisible && clickMatchingCenter(By.text("缓存音乐"))
        mark("缓存音乐", cacheOpened)
        if (cacheOpened) {
            // 工具栏标题可能不暴露语义；用页面特有的歌单与缓存操作共同确认到达。
            device.waitForIdle()
            var reached = device.wait(Until.hasObject(By.text("缓存歌单")), 12_000) &&
                device.hasObject(By.text("我喜欢的音乐"))
            if (!reached && device.hasObject(By.text("缓存音乐"))) {
                // 首次点击若被设置页过渡吞掉，直接重试文字节点中心。
                clickMatchingCenter(By.text("缓存音乐"))
                reached = device.wait(Until.hasObject(By.text("缓存歌单")), 12_000) &&
                    device.hasObject(By.text("我喜欢的音乐"))
            }
            mark("缓存音乐详情", reached)
            if (clickText("我喜欢的音乐", 5_000) || clickMatchingCenter(By.text("我喜欢的音乐"))) {
                device.waitForIdle()
                scrollAny()
                device.pressBack()
            }
            scrollAny()
            device.pressBack()
        }
        device.wait(Until.hasObject(By.text("设置")), 5_000)
        device.pressBack()
    }

    private fun MacrobenchmarkScope.clickText(text: String, timeoutMs: Long = 3_000): Boolean =
        clickNode(By.text(text), timeoutMs)

    private fun MacrobenchmarkScope.clickText(pattern: Pattern, timeoutMs: Long = 3_000): Boolean =
        clickNode(By.text(pattern), timeoutMs)

    private fun MacrobenchmarkScope.clickFirstText(values: List<String>, timeoutMs: Long): Boolean =
        values.firstOrNull { clickText(it, timeoutMs / values.size.coerceAtLeast(1)) } != null

    private fun MacrobenchmarkScope.clickDescription(pattern: Pattern, timeoutMs: Long = 3_000): Boolean =
        clickNode(By.desc(pattern), timeoutMs)

    private fun MacrobenchmarkScope.clickNode(selector: BySelector, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val target = device.findObjects(selector)
                .asSequence()
                .filter { node -> runCatching { node.isEnabled && node.visibleBounds.width() > 0 && node.visibleBounds.height() > 0 }.getOrDefault(false) }
                .sortedByDescending { node -> runCatching { node.visibleBounds.width() * node.visibleBounds.height() }.getOrDefault(0) }
                .firstNotNullOfOrNull { node ->
                    var candidate = node
                    while (!candidate.isClickable && candidate.parent != null) candidate = candidate.parent
                    candidate.takeIf { it.isClickable }
                }
            if (target != null && runCatching {
                    // Compose 文本节点常把点击交给父 View，点击可见父节点中心比 UiObject2.click 更稳定。
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

    private fun MacrobenchmarkScope.clickDescription(
        description: String,
        timeoutMs: Long = 3_000,
    ): Boolean {
        return clickNode(By.desc(description), timeoutMs)
    }

    private fun MacrobenchmarkScope.clickMatchingCenter(selector: BySelector): Boolean = runCatching {
        val node = device.findObjects(selector)
            .firstOrNull { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }
            ?: return@runCatching false
        val bounds = node.visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
        true
    }.getOrDefault(false)

    private fun MacrobenchmarkScope.openSearchEntity(selector: BySelector): Boolean {
        if (clickNode(selector, 12_000) && waitEntityPage()) return true
        return clickMatchingCenter(selector) && waitEntityPage()
    }

    private fun MacrobenchmarkScope.returnToRoot() {
        repeat(6) {
            val nested = device.hasObject(By.desc("返回")) ||
                device.hasObject(By.desc("返回首页")) ||
                device.hasObject(By.desc("搜索歌曲、歌手、专辑或歌单")) ||
                device.hasObject(By.desc("开启歌词")) ||
                device.hasObject(By.desc("关闭歌词")) ||
                device.hasObject(By.text("播放列表")) ||
                device.hasObject(By.text("查看歌手")) ||
                device.hasObject(By.text("查看专辑")) ||
                device.hasObject(By.text("综合"))
            if (!nested && (device.hasObject(By.desc("打开搜索")) ||
                    device.hasObject(By.desc("打开设置")))) return
            device.pressBack()
            device.waitForIdle()
        }
        if (!device.hasObject(By.text("每日推荐")) && device.hasObject(By.desc("首页"))) {
            clickDescription("首页")
            device.wait(Until.hasObject(By.text("每日推荐")), 5_000)
        }
    }

    private fun MacrobenchmarkScope.goHome() {
        if (device.hasObject(By.desc("首页"))) {
            clickDescription("首页", 5_000)
            device.wait(Until.hasObject(By.desc("打开搜索")), 5_000)
        }
    }

    private fun MacrobenchmarkScope.waitForLyricsControl(): Boolean {
        if (device.wait(Until.hasObject(By.desc("开启歌词")), 20_000) ||
            device.hasObject(By.desc("关闭歌词"))) return true
        device.click((device.displayWidth * 0.13f).toInt(), device.displayHeight - 80)
        return device.wait(Until.hasObject(By.desc("开启歌词")), 3_000) ||
            device.hasObject(By.desc("关闭歌词"))
    }

    private fun mark(page: String, captured: Boolean, detail: String = "") {
        val suffix = if (detail.isBlank()) "" else " [$detail]"
        Log.i(PROFILE_LOG, "设备=$DEVICE_LABEL 包=$PACKAGE_NAME 音源=QQ 页面=$page=${if (captured) "已采集" else "未采集"}$suffix")
    }

    private fun MacrobenchmarkScope.scrollAny(): Boolean = runCatching {
        val scrollable = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            ?: return@runCatching false
        scrollable.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
        true
    }.getOrDefault(false)

    private fun MacrobenchmarkScope.scrollUntilText(text: String, maxSwipes: Int): Boolean {
        repeat(maxSwipes + 1) {
            if (device.hasObject(By.text(text))) return true
            device.swipe(
                device.displayWidth / 2,
                (device.displayHeight * .82f).toInt(),
                device.displayWidth / 2,
                (device.displayHeight * .24f).toInt(),
                28,
            )
            device.waitForIdle()
            if (device.hasObject(By.text(text))) return true
            scrollAny()
        }
        return device.hasObject(By.text(text))
    }

    private fun MacrobenchmarkScope.restartAtHome() {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.wait(Until.hasObject(By.text("每日推荐")), 8_000)
    }

    private fun MacrobenchmarkScope.waitForQqHome(): Boolean = repeatUntil(3) {
        if (device.hasObject(By.text("每日推荐")) && device.hasObject(By.desc("打开搜索"))) {
            true
        } else {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            device.wait(Until.hasObject(By.text("每日推荐")), 8_000) &&
                device.hasObject(By.desc("打开搜索"))
        }
    }

    private inline fun repeatUntil(attempts: Int, action: () -> Boolean): Boolean {
        repeat(attempts) {
            if (action()) return true
            SystemClock.sleep(300)
        }
        return false
    }

    private fun MacrobenchmarkScope.scrollHomeFeed(): Boolean = runCatching {
        // 首页网格在不同 Compose 版本下可能不暴露 scrollable 节点，直接执行真实上滑兜底。
        device.swipe(device.displayWidth / 2, (device.displayHeight * .84f).toInt(),
            device.displayWidth / 2, (device.displayHeight * .24f).toInt(), 28)
        device.waitForIdle()
        true
    }.getOrDefault(false)

    private fun MacrobenchmarkScope.visibleTextCount(): Int = runCatching {
        device.findObjects(By.clazz("android.widget.TextView"))
            .count { runCatching { it.text?.isNotBlank() == true }.getOrDefault(false) }
    }.getOrDefault(0)

    private fun MacrobenchmarkScope.waitEntityPage(): Boolean =
        device.wait(Until.hasObject(By.desc("返回首页")), 8_000) ||
            device.wait(Until.hasObject(By.desc("返回")), 2_000)

    private fun MacrobenchmarkScope.markDevice() {
        Log.i(PROFILE_LOG, "设备=$DEVICE_LABEL 型号=${Build.MODEL} 设备代号=${Build.DEVICE} 分辨率=${device.displayWidth}x${device.displayHeight} 包=$PACKAGE_NAME 音源=QQ")
    }

    private fun MacrobenchmarkScope.isTablet(): Boolean = device.displayWidth >= 1600

    private fun MacrobenchmarkScope.scrollToTop() {
        runCatching {
            val scrollable = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
                ?: return@runCatching
            repeat(4) {
                if (!scrollable.scroll(Direction.UP, 1f)) return@runCatching
                device.waitForIdle()
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.shuyunr.musicone"
        const val PROFILE_QUERY = "RADWIMPS"
        const val PROFILE_LOG = "MusicOneProfile"
        val DEVICE_LABEL = if (Build.MODEL.contains("SM-S9110", ignoreCase = true)) "平板" else "手机"
    }
}
