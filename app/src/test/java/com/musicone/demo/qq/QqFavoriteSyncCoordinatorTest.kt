package com.musicone.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QqFavoriteSyncCoordinatorTest {
    @Test
    fun writesToFavoritePlaylistNeverOverlap() = runBlocking {
        val coordinator = QqFavoriteSyncCoordinator()
        var activeWrites = 0
        var maximumActiveWrites = 0

        coroutineScope {
            repeat(4) {
                launch(Dispatchers.Default) {
                    coordinator.runWrite {
                        activeWrites += 1
                        maximumActiveWrites = maxOf(maximumActiveWrites, activeWrites)
                        delay(20)
                        activeWrites -= 1
                    }
                }
            }
        }

        assertEquals(1, maximumActiveWrites)
    }
}
