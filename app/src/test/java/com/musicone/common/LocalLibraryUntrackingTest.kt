package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LocalLibraryUntrackingTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.LOCAL, "歌曲$id", "歌手", "专辑",
        240_000, 0, 0, "本", "")

    @Test fun plainReferencedSongsSkipConfirmation() {
        assertFalse(LocalRemovalPlan(listOf("a"), copied = 0, folders = emptyList(), all = false).needsConfirmation)
    }

    @Test fun copiedSongsWarnPermanentDeletion() {
        val plan = LocalRemovalPlan(listOf("a", "b"), copied = 1, folders = emptyList(), all = false)
        assertTrue(plan.needsConfirmation)
        assertEquals("从本地音乐移除 2 首歌？", plan.confirmTitle())
        assertTrue(plan.confirmDetail().contains("其中 1 首是复制到应用内导入的"))
        assertTrue(plan.confirmDetail().contains("永久删除"))
    }

    @Test fun trackedFolderIsDowngradedInConfirmation() {
        val plan = LocalRemovalPlan(listOf("a"), copied = 0, folders = listOf(LocalRemovalFolder("f", "下载", 12)), all = false)
        assertTrue(plan.needsConfirmation)
        assertTrue(plan.confirmDetail().contains("「下载」"))
        assertTrue(plan.confirmDetail().contains("其余 12 首改为逐首跟踪"))
    }

    @Test fun deletingPhoneStorageAlwaysConfirmsWithTotals() {
        val plan = LocalRemovalPlan(List(30) { "s$it" }, copied = 4, folders = emptyList(), all = true)
        assertTrue(plan.needsConfirmation)
        assertEquals("删除手机存储？", plan.confirmTitle())
        assertTrue(plan.confirmDetail().contains("全部 30 首"))
        assertTrue(plan.confirmDetail().contains("其中 4 首"))
    }

    @Test fun batchQueueRemovalHandlesCurrentSongLast() {
        val tracks = (1..4).map { track("l$it") }
        val states = MutableStateFlow(MusicOneUiState(currentTrack = tracks[1], queue = tracks))
        var started: MusicTrack? = null
        PlaybackQueueRemoval(states, { next, remaining -> started = next; states.value = remaining.copy(currentTrack = next) },
            { fail("仍有歌曲时不能清空") }, {}).removeAll(listOf(tracks[1], tracks[2]))
        assertEquals(tracks[3], started)
        assertEquals(listOf(tracks[0], tracks[3]), states.value.queue)
    }
}
