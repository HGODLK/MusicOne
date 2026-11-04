package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SourcePlayerUiContractTest {
    private val opening = SourcePlayerOpening(300, true, setOf(SourcePlayerSharedElement.ARTWORK))
    private val declaration = SourcePlayerUiDeclaration(true, true, true, opening)
    private open class Entry(kind: SourceKind = SourceKind.MAIN) : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.player", "播放器测试", kind, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    private fun entry(value: SourcePlayerUiDeclaration, kind: SourceKind = SourceKind.MAIN): SourceEntry =
        object : Entry(kind), SourcePlayerUiProvider {
            override val playerUiDeclaration = value
            @Composable override fun Player(context: SourcePlayerUiContext) {}
        }

    @Test fun legacyEntryRemainsAbsentAndApiOne() {
        assertEquals(1, SourceSdk.API_VERSION)
        val original = Entry()
        assertNull(SourceGuardedEntry(original, SourceOperationLease {}).playerUiProviderOrNull())
        SourceOptionalContractValidator.check(original)
    }

    @Test fun declarationIsTransparentThroughGuardAndCheckedBeforeUi() {
        val guarded = SourceGuardedEntry(entry(declaration), SourceOperationLease {})
        assertEquals(declaration, guarded.playerUiProviderOrNull()!!.playerUiDeclaration)
        SourceOptionalContractValidator.check(guarded)
        listOf(
            declaration.copy(supportsPhone = false, supportsTablet = false, supportsPhoneLandscape = false),
            declaration.copy(supportsPhone = false),
            declaration.copy(opening = opening.copy(durationMillis = -1)),
            declaration.copy(opening = opening.copy(barParticipates = false)),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SourceOptionalContractValidator.check(entry(invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceOptionalContractValidator.check(entry(declaration, SourceKind.SUPPLEMENTAL))
        }
        SourceOptionalContractValidator.check(entry(declaration.copy(supportsPhone = false, supportsPhoneLandscape = false)))
        SourceOptionalContractValidator.check(entry(declaration.copy(opening = SourcePlayerOpening(0, false, emptySet()))))
    }

    @Test fun instanceRemovalBlocksDeclarationRead() {
        var active = true
        val provider = SourceGuardedEntry(entry(declaration), SourceOperationLease { check(active) }).playerUiProviderOrNull()!!
        active = false
        assertThrows(IllegalStateException::class.java) { provider.playerUiDeclaration }
    }

    private class Context : SourcePlayerUiContext {
        var calls = 0
        override val snapshot = SourcePlayerSnapshot(null, false, false, "ALL", emptyList(), null, false, false, null)
        override val timeline = SourcePlayerTimeline("track", 5, 100, 2, false)
        override val form = SourcePlayerForm.PHONE_PORTRAIT
        override var safeDrawingInsets = SourcePlayerInsets(0.dp, 24.dp, 0.dp, 16.dp)
        override val transition = SourcePlayerTransition(1f, true, true, false)
        override var actions: SourcePlayerUiActions = object : SourcePlayerUiActions {
            override fun togglePlay() { calls++ }
            override fun next() { calls++ }
            override fun previous() { calls++ }
            override fun seekTo(trackId: String, generation: Long, positionMs: Long) { calls++ }
            override fun cyclePlaybackMode() { calls++ }
            override fun selectQuality(id: String) { calls++ }
            override fun refreshQualities() { calls++ }
            override fun openQueue() { calls++ }
            override fun toggleFavorite() { calls++ }
            override fun retryLyrics() { calls++ }
        }
        override fun dismiss() { calls++ }
        override fun onFailure(message: String) { calls++ }
        @Composable override fun Lyrics(modifier: Modifier) { calls++ }
        @Composable override fun SharedElement(element: SourcePlayerSharedElement, modifier: Modifier,
            content: @Composable () -> Unit) { calls++; content() }
    }

    @Test fun capturedActionsKeepTheirOriginalTrackTargetAfterContextUpdates() {
        val original = Context()
        val lease = SourcePlayerUiContextLease(original, "org.example.player", SourceOperationLease {})
        val captured = lease.actions
        val oldOriginal = original.actions
        var newCalls = 0
        original.actions = object : SourcePlayerUiActions by oldOriginal {
            override fun togglePlay() { newCalls++ }
        }
        captured.togglePlay()
        assertEquals(1, original.calls)
        assertEquals(0, newCalls)
        lease.actions.togglePlay()
        assertEquals(1, original.calls)
        assertEquals(1, newCalls)
    }

    @Test fun safeDrawingInsetsFollowTheCurrentOrientationWithinTheSameLease() {
        val original = Context()
        val lease = SourcePlayerUiContextLease(original, "org.example.player", SourceOperationLease {})
        assertEquals(SourcePlayerInsets(0.dp, 24.dp, 0.dp, 16.dp), lease.safeDrawingInsets)
        original.safeDrawingInsets = SourcePlayerInsets(24.dp, 0.dp, 16.dp, 0.dp)
        assertEquals(original.safeDrawingInsets, lease.safeDrawingInsets)
    }

    @Test fun unmountedAccountChangedAndRemovedContextsRejectEveryActionAndRead() {
        listOf("unmount", "account", "instance").forEach { change ->
            var active = true
            var account = 1
            val operations = SourceOperationLease { check(active) }.also { it.accountVersion = { account } }
            val original = Context()
            val lease = SourcePlayerUiContextLease(original, "org.example.player", operations)
            val actions = lease.actions
            assertEquals(original.snapshot, lease.snapshot)
            assertEquals(original.timeline, lease.timeline)
            assertEquals(original.form, lease.form)
            assertEquals(original.safeDrawingInsets, lease.safeDrawingInsets)
            assertEquals(original.transition, lease.transition)
            actions.togglePlay()
            when (change) {
                "unmount" -> lease.close()
                "account" -> account++
                else -> active = false
            }
            val blocked = listOf<() -> Unit>(
                { lease.snapshot }, { lease.timeline }, { lease.form }, { lease.safeDrawingInsets },
                { lease.transition }, { lease.actions },
                { actions.togglePlay() }, { actions.next() }, { actions.previous() },
                { actions.seekTo("track", 2, 10) }, { actions.cyclePlaybackMode() },
                { actions.selectQuality("LOSSLESS") }, { actions.refreshQualities() },
                { actions.openQueue() }, { actions.toggleFavorite() }, { actions.retryLyrics() },
                { lease.dismiss() }, { lease.onFailure("失败") },
            )
            blocked.forEach { action ->
                assertThrows(if (change == "account") CancellationException::class.java else IllegalStateException::class.java) { action() }
            }
            assertEquals(1, original.calls)
        }
    }
}
