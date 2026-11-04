package com.musicone

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PlayerInformationActionsTest {
    @Test fun phoneInformationAreaOnlyOpensAfterLyricsAreFullyClosed() {
        listOf(MusicSource.LOCAL, MusicSource.QQ, MusicSource.BILIBILI).forEach { source ->
            assertTrue(phonePlayerInformationMenuEnabled(source, false, 0f, 0f, false))
            assertFalse(phonePlayerInformationMenuEnabled(source, true, 1f, 1f, false))
            assertFalse(phonePlayerInformationMenuEnabled(source, false, .2f, .2f, false))
            assertFalse(phonePlayerInformationMenuEnabled(source, false, 0f, 0f, true))
        }
    }

    @Test fun onlineOffsetIsHiddenForTheOrdinaryClosedLyricsTitleMenu() {
        assertFalse(onlineLyricOffsetActionsVisible(false, true))
        assertFalse(onlineLyricOffsetActionsVisible(false, false))
        assertFalse(onlineLyricOffsetActionsVisible(true, false))
        assertTrue(onlineLyricOffsetActionsVisible(true, true))
    }

    @Test fun lyricPermissionControlsTheFullyShownHeaderIndependentlyOfMetadata() {
        listOf(MusicSource.LOCAL, MusicSource.BILIBILI).forEach { source ->
            assertTrue(playerInformationMenuEnabled(source, true, 1f, 1f, false, supportsLyricsActions = true))
            assertTrue(playerInformationMenuEnabled(source, false, 0f, 0f, false))
        }
        listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU).forEach { source ->
            assertFalse(playerInformationMenuEnabled(source, true, 1f, 1f, false, supportsLyricsActions = false))
        }
    }

    @Test fun onlineOffsetDoesNotRepeatTheLibraryTimelineShift() {
        val words = listOf(LyricWord(1000, 1400, "词"))
        val online = TimedLyric(1000, "在线", words = words)
        val manual = online.copy(text = "手动", libraryTimeline = true)
        val shifted = offsetOnlineLyricLines(listOf(online, manual), 100)
        assertEquals(1100L, shifted[0].timeMs)
        assertEquals(words, shifted[0].words)
        assertEquals(manual, shifted[1])
    }

    @Test fun manualAndOnlineLyricsAreIndependentAndManualWinsOnlyWhenPresent() = runBlocking {
        val manual = listOf(TimedLyric(1000, "手动", libraryTimeline = true))
        val online = listOf(TimedLyric(2000, "在线"))
        for (allowManual in listOf(false, true)) for (hasOnline in listOf(false, true)) {
            var libraryReads = 0
            var onlineReads = 0
            val emptyOverride = preferredLyrics(allowManual, hasOnline,
                { libraryReads++; emptyList() }, { onlineReads++; online })
            assertEquals(if (hasOnline) online else emptyList<TimedLyric>(), emptyOverride)
            assertEquals(if (allowManual) 1 else 0, libraryReads)
            assertEquals(if (hasOnline) 1 else 0, onlineReads)
            val existingOverride = preferredLyrics(allowManual, hasOnline, { manual }, { online })
            assertEquals(if (allowManual) manual else if (hasOnline) online else emptyList<TimedLyric>(), existingOverride)
        }
    }

    @Test fun openingClosingAndDraggingCannotTriggerTheHeaderMenu() {
        listOf(MusicSource.LOCAL, MusicSource.BILIBILI).forEach { source ->
            assertFalse(playerInformationMenuEnabled(source, true, .7f, 1f, false, supportsLyricsActions = true))
            assertFalse(playerInformationMenuEnabled(source, true, 1f, .7f, false, supportsLyricsActions = true))
            assertFalse(playerInformationMenuEnabled(source, false, .3f, .3f, false))
            assertFalse(playerInformationMenuEnabled(source, true, 1f, 1f, true, supportsLyricsActions = true))
            assertFalse(playerInformationMenuEnabled(source, false, 0f, 0f, true))
        }
    }
}
