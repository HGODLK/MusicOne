package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerInformationActionsTest {
    @Test fun onlineOffsetIsHiddenForTheOrdinaryClosedLyricsTitleMenu() {
        assertFalse(onlineLyricOffsetActionsVisible(false, true))
        assertFalse(onlineLyricOffsetActionsVisible(false, false))
        assertFalse(onlineLyricOffsetActionsVisible(true, false))
        assertTrue(onlineLyricOffsetActionsVisible(true, true))
    }

    @Test fun localAndBilibiliHeadersOpenTheMenuAfterLyricsAreFullyShown() {
        listOf(MusicSource.LOCAL, MusicSource.BILIBILI).forEach { source ->
            assertTrue(playerInformationMenuEnabled(source, true, 1f, 1f, false))
            assertTrue(playerInformationMenuEnabled(source, false, 0f, 0f, false))
        }
        listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU).forEach { source ->
            assertFalse(playerInformationMenuEnabled(source, true, 1f, 1f, false))
        }
    }

    @Test fun openingClosingAndDraggingCannotTriggerTheHeaderMenu() {
        listOf(MusicSource.LOCAL, MusicSource.BILIBILI).forEach { source ->
            assertFalse(playerInformationMenuEnabled(source, true, .7f, 1f, false))
            assertFalse(playerInformationMenuEnabled(source, true, 1f, .7f, false))
            assertFalse(playerInformationMenuEnabled(source, false, .3f, .3f, false))
            assertFalse(playerInformationMenuEnabled(source, true, 1f, 1f, true))
            assertFalse(playerInformationMenuEnabled(source, false, 0f, 0f, true))
        }
    }
}
