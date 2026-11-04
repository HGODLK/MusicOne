package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAccountSelectionTest {
    @Test
    fun 登录中途退出不切换音源() {
        val pending = PendingSourceLogin()
        pending.begin(MusicSource.QQ)
        assertNull(pending.settle { false })
    }

    @Test
    fun 登录成功才切换且只结算一次() {
        val pending = PendingSourceLogin()
        pending.begin(MusicSource.QQ)
        assertEquals(MusicSource.QQ, pending.settle { true })
        assertNull(pending.settle { true })
    }

    @Test
    fun 没有待登录音源时关闭页面不触发切换() {
        assertNull(PendingSourceLogin().settle { true })
    }

    @Test
    fun 校验失败后按短间隔重试() {
        val schedule = SourceAccountRefreshSchedule(validMs = 6_000, retryMs = 500)
        assertTrue(schedule.due("qq", 1_000))
        schedule.attempted("qq", 1_000)
        assertFalse(schedule.due("qq", 1_499))
        assertTrue(schedule.due("qq", 1_500))
    }

    @Test
    fun 校验成功后等满长周期且音源互不影响() {
        val schedule = SourceAccountRefreshSchedule(validMs = 6_000, retryMs = 500)
        schedule.attempted("qq", 1_000)
        schedule.succeeded("qq", 1_000)
        assertFalse(schedule.due("qq", 6_999))
        assertTrue(schedule.due("qq", 7_000))
        assertTrue(schedule.due("kugou", 1_001))
    }
}
