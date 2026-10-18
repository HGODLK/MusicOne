package com.musicone.demo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricTrackWindowStageTest {
    private fun window(id: String, slot: Float = 0f) = LyricWindow(LyricPresentation(
        source = MusicSource.QQ, id = id, durationMs = 10000L,
        lyrics = listOf(TimedLyric(0L, id)),
    ), slot)

    @Test fun mountedSourceAndTargetObserveHandoffWithoutRebuildingTheirReaders() {
        for (direction in listOf(-1f, 1f)) {
            val source = window("source")
            val target = window("target", direction)
            val held = LyricHeldTrackTransition()
            var current: LyricWindow? = source
            val outgoing = mutableListOf<LyricWindow>()
            var preparing = false
            var offset = 0f
            // 模拟 UI 节点保留旧回调，独立歌词时钟已推进事务。
            val sourceRole = { lyricTrackWindowStageRole(source, current, outgoing, held) }
            val targetRole = { lyricTrackWindowStageRole(target, current, outgoing, held) }
            val targetY = { lyricTrackWindowTranslation(targetRole(), preparing, held.active,
                800f, offset, target.slot) }
            assertEquals(LyricWindowStageRole.CURRENT, sourceRole())
            outgoing += source
            current = target
            preparing = true
            assertEquals(LyricWindowStageRole.OUTGOING, sourceRole())
            assertEquals(0f, targetY(), 0f)
            preparing = false
            for (remaining in listOf(1f, .8f, .35f, 0f)) {
                offset = -direction * (1f - remaining)
                val sourceY = lyricTrackWindowTranslation(sourceRole(), preparing, held.active,
                    800f, offset, source.slot)
                assertEquals(direction * 800f, targetY() - sourceY, .0001f)
            }
            outgoing.clear()
            assertEquals(LyricWindowStageRole.DETACHED, sourceRole())
            assertEquals(0f, targetY(), .0001f)
        }
    }

    @Test fun obsoleteHeldTargetAndReleasedSourceCannotReappearBeforeUiRemoval() = runBlocking {
        val source = window("source")
        val obsolete = window("obsolete")
        val target = window("target")
        val held = LyricHeldTrackTransition()
        var current: LyricWindow? = source
        val outgoing = mutableListOf<LyricWindow>()
        val sourceRole = { lyricTrackWindowStageRole(source, current, outgoing, held) }
        val obsoleteRole = { lyricTrackWindowStageRole(obsolete, current, outgoing, held) }
        held.request(source, obsolete, this)
        current = obsolete
        assertEquals(LyricWindowStageRole.OUTGOING, sourceRole())
        held.request(obsolete, target, this)
        current = target
        assertEquals(LyricWindowStageRole.DETACHED, obsoleteRole())
        assertEquals(LyricWindowStageRole.OUTGOING, sourceRole())
        outgoing += held.releaseForSlide()
        assertEquals(LyricWindowStageRole.OUTGOING, sourceRole())
        outgoing.clear()
        held.clearSlides()
        assertEquals(LyricWindowStageRole.DETACHED, sourceRole())
        assertEquals(LyricWindowStageRole.DETACHED, obsoleteRole())
    }
}
