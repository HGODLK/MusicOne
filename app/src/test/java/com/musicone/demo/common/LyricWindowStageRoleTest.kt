package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWindowStageRoleTest {
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
