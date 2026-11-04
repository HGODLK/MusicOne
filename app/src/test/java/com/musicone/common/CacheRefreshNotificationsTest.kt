package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CacheRefreshNotificationsTest {
    @Test fun imageFeedAndPrimaryMetadataRefreshManagementWithoutInvalidatingAudio() {
        val changes = CacheRefreshNotifications()
        repeat(3) { changes.cacheChanged() }
        assertEquals(3L, changes.cacheRevision.value)
        assertEquals(0L, changes.audioAvailabilityRevision.value)
    }

    @Test fun audioEvictionDuringImageWriteStillInvalidatesAvailability() {
        val changes = CacheRefreshNotifications()
        changes.audioChanged()
        changes.cacheChanged()
        assertEquals(2L, changes.cacheRevision.value)
        assertEquals(1L, changes.audioAvailabilityRevision.value)
    }

    @Test fun addingAndRemovingAudioRefreshBothConsumers() {
        val changes = CacheRefreshNotifications()
        changes.audioChanged()
        changes.audioChanged()
        assertEquals(2L, changes.cacheRevision.value)
        assertEquals(2L, changes.audioAvailabilityRevision.value)
    }

    @Test fun batchCoalescesAudioRemovalButImageOnlyCleanupDoesNotInvalidateAudio() {
        val changes = CacheRefreshNotifications()
        changes.beginAudioRemovalBatch()
        changes.finishAudioRemovalBatch()
        assertEquals(1L, changes.cacheRevision.value)
        assertEquals(0L, changes.audioAvailabilityRevision.value)
        changes.beginAudioRemovalBatch()
        repeat(3) { changes.audioRemoved() }
        assertEquals(0L, changes.audioAvailabilityRevision.value)
        changes.finishAudioRemovalBatch()
        assertEquals(2L, changes.cacheRevision.value)
        assertEquals(1L, changes.audioAvailabilityRevision.value)
        changes.audioRemoved()
        assertEquals(2L, changes.audioAvailabilityRevision.value)
    }

    @Test fun removalRacingBatchCompletionNeverLosesAvailabilityNotification() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            repeat(200) {
                val changes = CacheRefreshNotifications()
                val start = CountDownLatch(1)
                changes.beginAudioRemovalBatch()
                val removal = worker.submit {
                    start.await()
                    changes.audioRemoved()
                }
                start.countDown()
                changes.finishAudioRemovalBatch()
                removal.get(2, TimeUnit.SECONDS)
                assertEquals(1L, changes.audioAvailabilityRevision.value)
            }
        } finally { worker.shutdownNow() }
    }
}
