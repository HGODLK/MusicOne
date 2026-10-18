package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LyricWindowStageRoleTest {
    @Test fun seekBoundaryKeepsTheSourceCoordinatesWithoutUiRecomposition() = runBlocking {
        for (fps in listOf(15, 30, 60, 120)) for (direction in listOf(-1f, 1f)) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                val controller = LyricLayerTransitionController("track", 1)
                val source = controller.currentPane
                // 回调在节点仍是当前窗口时创建，整段交接不替换它。
                val sourceBoundary = { controller.paneTranslation(source) }
                val operation = controller.beginTransition(20, direction, 800f, this)!!
                try {
                    val target = controller.currentPane
                    controller.updatePlaybackLine(20, present = false)
                    assertEquals(0f, sourceBoundary(), 0f)
                    target.ready.complete(Unit)
                    yield()
                    var frame = 0L
                    while (!operation.isCompleted && frame < fps * 3L) {
                        yield()
                        clock.sendFrame(++frame * (1_000_000_000L / fps))
                        yield()
                        if (controller.state == LyricLayerTransitionState.TRANSITIONING) {
                            assertEquals(direction * 800f,
                                controller.paneTranslation(target) - sourceBoundary(), .0001f)
                        }
                    }
                    assertEquals(LyricLayerTransitionState.NORMAL, controller.state)
                    assertEquals(0f, controller.paneTranslation(target), 0f)
                    assertEquals(0f, sourceBoundary(), 0f)
                } finally {
                    operation.cancelAndJoin()
                }
            }
        }
    }

    @Test fun repeatedSeekBeforeTargetDrawPreservesTheActuallyVisibleSource() = runBlocking {
        val controller = LyricLayerTransitionController("track", 1)
        val source = controller.currentPane
        val first = controller.beginTransition(20, 1f, 800f, this)!!
        val hidden = controller.currentPane
        val latest = controller.beginTransition(40, -1f, 800f, this)!!
        try {
            assertSame(source, controller.outgoingPane)
            assertEquals(LyricWindowStageRole.DETACHED,
                lyricWindowStageRole(hidden.id, controller.currentPane.id, controller.outgoingPane?.id))
            assertEquals(1f, lyricWindowStageRole(source.id, controller.currentPane.id, controller.outgoingPane?.id)
                .alpha(true, controller.seekOffset.value, controller.seekTravel), 0f)
        } finally {
            first.cancelAndJoin()
            latest.cancelAndJoin()
        }
    }

    @Test fun sourceStaysVisibleWhileUiHasNotMountedTheNewTarget() {
        val sourceId = 1L
        // UI 仍只有旧节点，歌词时钟已经发布下一次寻焦事务。
        val role = lyricWindowStageRole(sourceId, currentId = 2L, outgoingId = sourceId)
        assertEquals(1f, role.alpha(preparing = true, offset = 0f, travel = 800f), 0f)
        assertEquals(0f, role.translation(preparing = true, offset = 0f, travel = 800f), 0f)
        assertEquals(0f, lyricWindowStageRole(2L, 2L, sourceId)
            .alpha(preparing = true, offset = 0f, travel = 800f), 0f)
    }

    @Test fun unequalUiAndLyricRatesKeepTheSameCrossfadeInBothDirections() {
        for (travel in listOf(-800f, 800f)) {
            // 整段运动不更新 UI 挂载时的角色，绘制仍读取当前事务。
            val source = lyricWindowStageRole(1L, 2L, 1L)
            val target = lyricWindowStageRole(2L, 2L, 1L)
            for (remaining in listOf(1f, .75f, .5f, .25f, 0f)) {
                val offset = travel * remaining
                assertEquals(1f, source.alpha(false, offset, travel) + target.alpha(false, offset, travel), .00001f)
                assertEquals(travel, target.translation(false, offset, travel) - source.translation(false, offset, travel), .00001f)
            }
        }
    }

    @Test fun nodesAwaitingUiRemovalCannotFlashAfterFinishOrReplacement() {
        val removed = lyricWindowStageRole(1L, currentId = 2L, outgoingId = null)
        assertEquals(0f, removed.alpha(preparing = false, offset = 0f, travel = 0f), 0f)
        val replaced = lyricWindowStageRole(1L, currentId = 3L, outgoingId = 2L)
        assertEquals(0f, replaced.alpha(preparing = true, offset = 400f, travel = 800f), 0f)
        assertEquals(1f, lyricWindowStageRole(2L, 3L, 2L).alpha(true, 400f, 800f), 0f)
    }

    @Test fun cancellationRestoresTheSourceBeforeUiRecomposition() {
        val restored = lyricWindowStageRole(1L, currentId = 1L, outgoingId = null)
        assertEquals(1f, restored.alpha(preparing = false, offset = 0f, travel = 0f), 0f)
        assertEquals(0f, restored.translation(preparing = false, offset = 0f, travel = 0f), 0f)
        assertEquals(0f, lyricWindowStageRole(2L, 1L, null).alpha(false, 0f, 0f), 0f)
    }
}
