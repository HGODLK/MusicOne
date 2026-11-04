package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqMusicFeedRequestsTest {
    @Test fun retriesTransientFailureWithTheLegacyDelays() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = loadQqMusicFeedWithRetry(wait = { waits += it }) {
            calls++
            if (calls < 3) error("暂时失败")
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(3, calls)
        assertEquals(listOf(600L, 1200L), waits)
    }

    @Test fun cancellationAndLoginFailureDoNotRetry() = runBlocking {
        for (failure in listOf(CancellationException("取消"), PlatformApiException("请登录", 301))) {
            var calls = 0
            try {
                loadQqMusicFeedWithRetry(wait = { fail("不应等待重试") }) { calls++; throw failure }
                fail("应传播原错误")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun stopsAfterThirdFailure() = runBlocking {
        var calls = 0
        val failure = IllegalStateException("不可用")
        try {
            loadQqMusicFeedWithRetry(wait = {}) { calls++; throw failure }
            fail("应传播最终错误")
        } catch (caught: Exception) { assertSame(failure, caught) }
        assertEquals(3, calls)
    }
}
