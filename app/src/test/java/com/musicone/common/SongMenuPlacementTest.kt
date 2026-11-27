package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌曲菜单锚点定位逻辑的单元测试。
 *
 * 覆盖首次弹出的垂直定位、编辑态高度变化时的中心锁定（对称展开/收缩）、
 * 居中定位的上下边界夹紧以及水平方向的右对齐与居中策略。
 */
class SongMenuPlacementTest {
    /** 断言浮点结果，统一使用 0.01f 容差。 */
    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertEquals(message, expected, actual, 0.01f)
    }

    @Test
    fun initialTopPlacesMenuAboveAnchorAndRespectsLimits() {
        // 常规情况：菜单底部与锚点顶部留出 gap 间距，即 500 - 160 - 8 = 332
        assertClose(
            332f,
            songMenuInitialTop(
                anchorTop = 500f,
                menuHeight = 160f,
                topLimit = 60f,
                bottomLimit = 800f,
                gap = 8f,
            ),
            "菜单应放置在锚点上方并留出间距",
        )

        // 锚点非常靠上：理论值为 100 - 160 - 8 = -68，需被夹紧到上边界 60
        assertClose(
            60f,
            songMenuInitialTop(
                anchorTop = 100f,
                menuHeight = 160f,
                topLimit = 60f,
                bottomLimit = 800f,
                gap = 8f,
            ),
            "超出上边界时应夹紧到 topLimit",
        )
    }

    @Test
    fun expandingHeightGrowsSymmetricallyAroundLockedCenter() {
        // 初始定位后锁定中心：332 + 160 / 2 = 412
        val initialTop = 332f
        val initialHeight = 160f
        val centerY = initialTop + initialHeight / 2f
        assertClose(412f, centerY, "锁定中心应为 412")

        // 展开到 300 高：顶部应上移 70 到 262
        val expandedHeight = 300f
        val top = songMenuCenteredTop(centerY, expandedHeight, 60f, 800f)
        assertClose(262f, top, "展开后顶部应为 262")

        // 中心保持不动，即上下对称各撑开 70
        assertClose(centerY, top + expandedHeight / 2f, "展开后中心不应位移")
        assertClose(70f, initialTop - top, "上侧应撑开 70")
    }

    @Test
    fun shrinkingHeightContractsSymmetricallyAroundLockedCenter() {
        // 保持同一锁定中心 412
        val centerY = 412f

        // 收缩到 120 高：顶部应下移 40 到 352
        val shrunkHeight = 120f
        val top = songMenuCenteredTop(centerY, shrunkHeight, 60f, 800f)
        assertClose(352f, top, "收缩后顶部应为 352")

        // 中心依然未位移
        assertClose(centerY, top + shrunkHeight / 2f, "收缩后中心不应位移")
    }

    @Test
    fun centeredExpansionClampsToTopAndBottomBounds() {
        // 贴近顶部：120 - 200 / 2 = 20，被夹紧到 60
        assertClose(
            60f,
            songMenuCenteredTop(
                centerY = 120f,
                menuHeight = 200f,
                topLimit = 60f,
                bottomLimit = 800f,
            ),
            "接近顶部时应夹紧到 topLimit",
        )

        // 贴近底部：740 - 200 / 2 = 640，菜单底部 840 超过 800，故顶部夹紧到 600
        assertClose(
            600f,
            songMenuCenteredTop(
                centerY = 740f,
                menuHeight = 200f,
                topLimit = 60f,
                bottomLimit = 800f,
            ),
            "接近底部时应保证菜单底部不越过 bottomLimit",
        )
    }

    @Test
    fun horizontalPlacementAlignsToAnchorOrCentersForEditing() {
        // 普通模式右对齐锚点右侧：400 - 300 = 100
        assertClose(
            100f,
            songMenuHorizontalX(
                anchorRight = 400f,
                menuWidth = 300f,
                leftLimit = 12f,
                rightLimit = 468f,
                centered = false,
            ),
            "普通模式应右对齐锚点",
        )

        // 左侧越界：100 - 300 = -200，被夹紧到左边界 12
        assertClose(
            12f,
            songMenuHorizontalX(
                anchorRight = 100f,
                menuWidth = 300f,
                leftLimit = 12f,
                rightLimit = 468f,
                centered = false,
            ),
            "左侧越界时应夹紧到 leftLimit",
        )

        // 编辑态在可用区间内居中：12 + (468 - 12 - 360) / 2 = 60
        assertClose(
            60f,
            songMenuHorizontalX(
                anchorRight = 400f,
                menuWidth = 360f,
                leftLimit = 12f,
                rightLimit = 468f,
                centered = true,
            ),
            "编辑态应在可用宽度内居中",
        )
    }
}