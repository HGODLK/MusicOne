package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TabletPlaylistLayoutTest {
    @Test fun delayedDetailsKeepTheFirstLandingAndCachedLandingIdentical() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val selected = mutableStateOf<MusicPlaylist?>(null)
        val motion = PageMotion(scope, false, 320, 280)
        val cards = PlaylistCardTransition(scope)
        val navigation = RecommendationNavigation(selected, motion, cards)
        val summary = MusicPlaylist("qq-42", MusicSource.QQ, "歌单", "", "", 0, 0L, 0L, "歌", emptyList())
        val detail = summary.copy(title = "需要两行展示的完整歌单名称", subtitle = "歌单作者",
            description = "首次异步返回的长简介".repeat(30))
        motion.updateHost(Rect(0f, 0f, 1200f, 1100f))
        motion.sources[summary.id] = MotionAnchor(Rect(20f, 20f, 180f, 180f))
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() }
        }
        // 内容直接补全；布局仅使用字体对应的预留高度，不能再使用简介或更多按钮的实际高度。
        fun publishLayout(wide: Boolean): Pair<Rect, Int> {
            val geometry = tabletPlaylistGeometry(if (wide) 630 else 680,
                if (wide) 760 else 1040, 100, 72, 114, if (wide) 12 else 0)
            val side = geometry.coverHeight.toFloat()
            val bounds = Rect(24f, 12f, 24f + side, 12f + side)
            motion.targets[summary.id] = MotionAnchor(bounds)
            return bounds to geometry.actionsTop
        }
        for (wide in listOf(true, false)) {
            for (arrivalFrame in listOf(0, 8, 30)) {
                navigation.open(summary)
                val initial = publishLayout(wide)
                frames(arrivalFrame)
                navigation.update(detail)
                assertEquals(detail, selected.value)
                assertEquals(initial, publishLayout(wide))
                frames(40)
                assertEquals(MotionPhase.SHOWN, motion.phase)
                assertEquals(initial.first, motion.targetSnapshot[summary.id]?.bounds)
                assertEquals(initial.first, motion.targets[summary.id]?.bounds)
                navigation.close()
                frames(45)
                // 再次打开直接获得缓存详情，仍应与首次打开使用完全相同的落点及按钮位置。
                navigation.open(detail)
                assertEquals(initial, publishLayout(wide))
                frames(40)
                assertEquals(initial.first, motion.targetSnapshot[summary.id]?.bounds)
                navigation.close()
                frames(45)
            }
        }
        coroutineContext.cancelChildren()
    }

    @Test fun geometryKeepsActionsVisibleEvenWhenTheWindowCannotFitTheCover() {
        for (height in listOf(0, 50, 100, 200, 480, 760)) {
            val geometry = tabletPlaylistGeometry(630, height, 224, 104, 160, 12)
            assertTrue(geometry.actionsTop >= 0)
            assertTrue(geometry.actionsTop + geometry.actionsHeight <= height)
            assertTrue(geometry.descriptionHeight >= 0)
            if (height >= 104) assertEquals(104, geometry.actionsHeight)
        }
    }

    @Test fun landscapeCoverUsesSpaceAfterInformationInsteadOfAPercentageLimit() {
        val side = tabletPlaylistCoverSide(630, 760, 80, 72, 64, 24)
        assertEquals(520, side)
        assertTrue(side > (760 - 72) * .55f)
        assertEquals(760, side + 80 + 72 + 64 + 24)
    }

    @Test fun shortWindowsAndLargeTextKeepPlaybackActionsInsideTheViewport() {
        for (information in listOf(80, 144, 224)) {
            for (actions in listOf(72, 104)) {
                val side = tabletPlaylistCoverSide(630, 480, information, actions, 80, 24)
                assertTrue(side >= 0)
                assertTrue(side + information + actions + 24 <= 480)
            }
        }
    }

    @Test fun portraitCoverIsSquareAndLeavesRoomForMetadataAndButtons() {
        val side = tabletPlaylistCoverSide(680, 1040, 108, 72, 72, 12)
        assertEquals(680, side)
        assertTrue(side + 108 + 72 + 72 + 12 <= 1040)
        assertEquals(0, tabletPlaylistCoverSide(680, 200, 108, 72, 72, 12))
    }
}
