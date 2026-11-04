package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueIndexFontSizeTest {
    @Test fun 队列序号按位数缩小() {
        assertEquals(16f, queueIndexFontSize(1).value, 0f)
        assertEquals(16f, queueIndexFontSize(99).value, 0f)
        assertEquals(14f, queueIndexFontSize(100).value, 0f)
        assertEquals(12f, queueIndexFontSize(1_000).value, 0f)
        assertEquals(10f, queueIndexFontSize(10_000).value, 0f)
    }
}
