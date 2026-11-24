package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqExpiryRecoveryTest {
    private var now = 1_000_000L
    private val recovery = QqExpiryRecovery { now }

    @Test fun 失效后先短暂等待再复核之后逐步退避() {
        assertFalse(recovery.due())
        recovery.expired()
        now += 19_000L
        assertFalse(recovery.due())
        now += 1_000L
        assertTrue(recovery.due())
        recovery.attempted(); recovery.failed()
        now += 119_000L
        assertFalse(recovery.due())
        now += 1_000L
        assertTrue(recovery.due())
    }

    @Test fun 网络恢复提前复核但与上次间隔至少三十秒() {
        recovery.expired()
        now += 20_000L
        recovery.attempted(); recovery.failed()
        now += 10_000L
        recovery.networkAvailable()
        assertFalse(recovery.due())
        now += 20_000L
        assertTrue(recovery.due())
    }

    @Test fun 未失效时网络恢复不触发复核且恢复后停止() {
        recovery.networkAvailable()
        now += 3_600_000L
        assertFalse(recovery.due())
        recovery.expired(); recovery.recovered()
        now += 3_600_000L
        assertFalse(recovery.due())
    }

    @Test fun 失效原因去掉通用前缀且不附加占位码() {
        assertEquals("账号校验被拒：登录态接口返回 1000",
            PlatformApiException("QQ 音乐登录状态已失效（账号校验被拒：登录态接口返回 1000）", 301).qqExpiryReason())
        assertEquals("续票被拒：登录票据已过期（业务码 104400）",
            PlatformApiException("续票被拒：登录票据已过期", 104400).qqExpiryReason())
        assertEquals("票据失效", PlatformApiException("票据失效", 1000).qqExpiryReason().removeSuffix("（业务码 1000）"))
    }
}
