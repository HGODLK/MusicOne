package com.musicone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MenuArtistLookupCacheTest {
    @Test fun repeatedAndConcurrentMenusReuseTheSamePreparedValue() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = MenuArtistLookupCache<Any>(scope)
            val gate = CompletableDeferred<Unit>()
            var requests = 0
            val first = async { cache.load("qq:artist") { requests++; gate.await(); Any() } }
            val second = async { cache.load("qq:artist") { requests++; Any() } }
            gate.complete(Unit)
            val prepared = first.await()
            assertSame(prepared, second.await())
            assertSame(prepared, cache.load("qq:artist") { fail("重复打开不能重新请求"); Any() })
            assertSame(prepared, cache.peek("qq:artist"))
            assertEquals(1, requests)
            assertNotSame(prepared, cache.load("netease:artist") { Any() })
        } finally { scope.cancel() }
    }

    @Test fun failedRequestCanRetryWithoutPoisoningTheHostScope() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = MenuArtistLookupCache<String>(scope)
            try { cache.load("qq:artist") { error("临时失败") }; fail() }
            catch (_: IllegalStateException) { }
            assertEquals("头像", cache.load("qq:artist") { "头像" })
        } finally { scope.cancel() }
    }

    @Test fun closingMenuDoesNotRestartAnInFlightAvatarLookup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val cache = MenuArtistLookupCache<String>(scope)
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            var requests = 0
            val opening = async { cache.load("qq:artist") { requests++; started.complete(Unit); response.await() } }
            started.await()
            opening.cancelAndJoin()
            response.complete("头像")
            assertEquals("头像", cache.load("qq:artist") { requests++; "重复请求" })
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }
}
