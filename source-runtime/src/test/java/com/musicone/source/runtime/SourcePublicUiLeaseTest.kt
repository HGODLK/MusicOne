package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.musicone.source.sdk.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SourcePublicUiLeaseTest {
    private val id = "org.example.publicui"

    @Test fun rootPageExtensionIsOnlyExposedWhenHostImplementsIt() {
        assertFalse(Fixture().lease.contentUi!!.pages is SourceRootPageUi)
        val fixture = Fixture(rootPages = true)
        assertTrue(fixture.lease.contentUi!!.pages is SourceRootPageUi)
        fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.My(id))
        assertEquals(SourcePageRequest.My(id), fixture.pages.request)
    }

    @Test fun rootScrollCallbackCannotReadAfterUnmountOrAccountChange() {
        var active = true
        val operations = SourceOperationLease { check(active) }
        var account = "first"
        operations.accountVersion = { account }
        val callback = SourceRootScrollCallback(operations.deferredCheck())
        val position = SourceRootScrollPosition(2, 31)
        assertEquals(position, callback.read { position })
        account = "second"
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            callback.read { error("换号后不能读取旧位置") }
        }
        val unmounted = SourceRootScrollCallback(operations.deferredCheck())
        unmounted.close()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            unmounted.read { error("卸载后不能读取旧位置") }
        }
        val removed = SourceRootScrollCallback(operations.deferredCheck())
        active = false
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            removed.read { error("实例失效后不能读取旧位置") }
        }
    }

    @Test fun rootScrollCallbackRejectsInvalidationDuringPositionRead() {
        val callback = SourceRootScrollCallback {}
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            callback.read { callback.close(); SourceRootScrollPosition(0, 0) }
        }
    }

    @Test fun customAndDefaultPagesBothExposePublicUi() {
        listOf(false, true).forEach { custom ->
            val fixture = Fixture(custom)
            fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.Search(id))
            assertEquals(id, fixture.pages.guard?.sourceId)
            assertEquals(SourcePageRequest.Search(id), fixture.pages.request)
            assertEquals(id, fixture.lease.contentUi!!.components!!.forSource(id).sourceId)
        }
    }

    @Test fun anotherSourcesPageAndComponentsAreRejectedBeforeHostInvocation() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.Home("another"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.lease.contentUi!!.components!!.forSource("another")
        }
        assertNull(fixture.pages.request)
        assertEquals(0, fixture.components.created)
    }

    @Test fun removingSourceInvalidatesCapturedScopeAndSuppressesClosedCallback() {
        val fixture = Fixture()
        var closed = 0
        fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.Home(id)) { closed++ }
        val components = fixture.lease.contentUi!!.components!!.forSource(id)
        fixture.lease.invalidate()
        assertThrows(IllegalStateException::class.java) { components.sourceId }
        assertFalse(fixture.pages.guard!!.isCurrent())
        fixture.pages.closed()
        assertEquals(0, closed)
    }

    @Test fun accountChangeSuppressesPendingCloseButNewRequestUsesNewAccount() {
        val fixture = Fixture()
        var account = 1
        fixture.lease.operations.accountVersion = { account }
        var closed = 0
        fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.My(id)) { closed++ }
        account++
        fixture.pages.closed()
        assertEquals(0, closed)
        fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.My(id)) { closed++ }
        fixture.pages.closed()
        assertEquals(1, closed)
    }

    @Test fun callerLifecycleCheckCannotReplaceRuntimeCheck() {
        val fixture = Fixture()
        var checks = 0
        val components = fixture.lease.contentUi!!.components!!.forSource(id) { checks++ }
        assertEquals(id, components.sourceId)
        assertEquals(1, checks)
        fixture.lease.invalidate()
        assertThrows(IllegalStateException::class.java) { components.sourceId }
        assertEquals(1, checks)
    }

    @Test fun loadedHomeExtensionAndRealTrackArgumentsSurviveRuntimeScope() {
        val fixture = Fixture(loadedHome = true)
        val scope = fixture.lease.contentUi!!.components!!.forSource(id)
        assertTrue(scope is SourceLoadedHomeComponents)
        val collection = SourceCollection(TrackIdentity(id, "account", "daily"), "daily", "每日推荐")
        val tracks = listOf("first", "second").map { key ->
            SourceTrackDetails(SourceTrack(TrackIdentity(id, "account", key), key), key)
        }
        listOf("DailyCard", "PersonalizedCards").forEach { name ->
            invokeLoadedCard(scope as SourceLoadedHomeComponents, name, collection, tracks)
            assertSame(collection, fixture.components.loadedCollection)
            assertSame(tracks, fixture.components.loadedTracks)
        }
        assertEquals(listOf("DailyCard", "PersonalizedCards"), fixture.components.loadedCalls)
    }

    @Test fun invalidatedLeaseBlocksBothLoadedHomeEntriesBeforeHostConsumesTracks() {
        val fixture = Fixture(loadedHome = true)
        var callerChecks = 0
        val scope = fixture.lease.contentUi!!.components!!.forSource(id) { callerChecks++ }
            as SourceLoadedHomeComponents
        val collection = SourceCollection(TrackIdentity(id, null, "daily"), "daily", "每日推荐")
        val tracks = listOf(SourceTrackDetails(SourceTrack(TrackIdentity(id, null, "first"), "首曲"), "first"))
        invokeLoadedCard(scope, "DailyCard", collection, tracks)
        val checksBeforeRemoval = callerChecks
        fixture.lease.invalidate()
        listOf("DailyCard", "PersonalizedCards").forEach { name ->
            assertThrows(IllegalStateException::class.java) { invokeLoadedCard(scope, name, collection, tracks) }
        }
        assertEquals(listOf("DailyCard"), fixture.components.loadedCalls)
        assertEquals(checksBeforeRemoval, callerChecks)
    }

    // 仅调用编译后的公开入口检查租约转发；代理不运行 Compose 布局或动画。
    private fun invokeLoadedCard(scope: SourceLoadedHomeComponents, name: String,
        collection: SourceCollection, tracks: List<SourceTrackDetails>) {
        val method = SourceLoadedHomeComponents::class.java.methods.single { it.name == name }
        val arguments = method.parameterTypes.map { type ->
            when {
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Modifier::class.java -> Modifier
                type.name == "kotlin.jvm.functions.Function0" -> ({})
                else -> null
            }
        }.toTypedArray()
        arguments[0] = collection
        arguments[1] = tracks
        try { method.invoke(scope, *arguments) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun validationInstanceCannotOpenPageOrCreateComponentScope() {
        val fixture = Fixture(validationOnly = true)
        assertThrows(IllegalStateException::class.java) {
            fixture.lease.contentUi!!.pages!!.open(SourcePageRequest.Home(id))
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.lease.contentUi!!.components!!.forSource(id)
        }
    }

    private inner class Fixture(custom: Boolean = true, validationOnly: Boolean = false, loadedHome: Boolean = false,
        rootPages: Boolean = false) {
        val pages = if (rootPages) CapturingRootPages() else CapturingPages()
        val components = CapturingComponents(loadedHome)
        private val sharedUi = Proxy.newProxyInstance(SourceContentUi::class.java.classLoader,
            arrayOf(SourceContentUi::class.java)) { _, method, _ ->
            when (method.name) { "getPages" -> pages; "getComponents" -> components; else -> null }
        } as SourceContentUi
        private val host = object : SourceHost {
            override val contentUi = sharedUi
            override fun play(tracks: List<SourceTrack>, startIndex: Int) {}
            override fun importTracks(tracks: List<SourceTrack>, destinationId: String) {}
            override fun exitSource() {}
            override fun reportError(message: String) {}
        }
        val lease = SourceHostLease(host, validationOnly,
            SourceDescriptor(id, "公共界面测试", SourceKind.MAIN, 1, "1", emptySet(), useCustomPages = custom))
    }

    private open class CapturingPages : SourcePageUi {
        var request: SourcePageRequest? = null
        var guard: SourceHostCallbackGuard? = null
        var closed: () -> Unit = {}
        override fun open(request: SourcePageRequest, onClosed: () -> Unit) {
            this.request = request
            guard = SourceHostCallbackGuard.capture()
            closed = onClosed
        }
        @Composable override fun Content(request: SourcePageRequest, modifier: Modifier,
            bottomInsetDp: Float, onClose: () -> Unit) {}
    }

    private class CapturingRootPages : CapturingPages(), SourceRootPageUi {
        @Composable override fun RootContent(request: SourcePageRequest, modifier: Modifier,
            bottomInsetDp: Float, onClose: () -> Unit) {}
        @Composable override fun ReportScroll(page: String, position: () -> SourceRootScrollPosition) {}
    }

    private class CapturingComponents(private val loadedHome: Boolean = false) : SourceComponentUi {
        var created = 0
        val loadedCalls = mutableListOf<String>()
        var loadedCollection: SourceCollection? = null
        var loadedTracks: List<*>? = null
        override fun forSource(sourceId: String, checkAccess: () -> Unit): SourceComponentScope {
            created++
            val interfaces = if (loadedHome) arrayOf(SourceComponentScope::class.java, SourceLoadedHomeComponents::class.java)
                else arrayOf(SourceComponentScope::class.java)
            return Proxy.newProxyInstance(SourceComponentScope::class.java.classLoader,
                interfaces) { _, method, arguments ->
                checkAccess()
                if (method.name == "DailyCard" || method.name == "PersonalizedCards") {
                    loadedCalls += method.name
                    loadedCollection = arguments!![0] as SourceCollection
                    loadedTracks = arguments[1] as List<*>
                }
                if (method.name == "getSourceId") sourceId else null
            } as SourceComponentScope
        }
    }
}
