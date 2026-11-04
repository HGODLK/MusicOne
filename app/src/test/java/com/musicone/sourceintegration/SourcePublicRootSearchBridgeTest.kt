package com.musicone.sourceintegration

import androidx.compose.ui.geometry.Offset
import com.musicone.source.sdk.SourcePageRequest
import com.musicone.source.sdk.SourceSessionStatus
import org.junit.Assert.*
import org.junit.Test

class SourcePublicRootSearchBridgeTest {
    private class Fixture {
        val owner = SourcePublicRootSearchFrame("kugou", Any(), "account-a", canStart = true)
        val bridge = SourcePublicRootSearchBridge().apply { frame = owner }
        val request = SourcePageRequest.Search("kugou", query = "测试歌曲", submit = true)
        val surface = SourcePublicSurfaceContext(active = true, interactive = true, rootOffset = Offset.Zero)
        fun call() = SourcePublicRootSearchCall(request, owner)
    }

    @Test fun explicitSearchRequiresAnActiveSeparateRootWithMatchingReadySource() {
        val fixture = Fixture()
        with(fixture) {
            assertTrue(bridge.canHandle(request, surface))
            assertFalse(bridge.canHandle(request, null))
            assertFalse(bridge.canHandle(request, surface.copy(active = false)))
            assertFalse(bridge.canHandle(request, surface.copy(separateRoot = false)))
            assertFalse(bridge.canHandle(SourcePageRequest.Search("qq"), surface))
            bridge.frame = owner.copy(canStart = false)
            assertFalse(bridge.canHandle(request, surface))
            bridge.frame = owner.copy(instance = null)
            assertFalse(bridge.canHandle(request, surface))
            bridge.frame = null
            assertFalse(bridge.canHandle(request, surface))
        }
    }

    @Test fun detailsAndOverlaysBlockNewStartsWithoutDroppingTheRegisteredSearch() {
        val fixture = Fixture()
        with(fixture) {
            val registered = call()
            assertTrue(bridge.register(registered))
            registered.activated = true
            bridge.frame = owner.copy(canStart = false)

            assertTrue(bridge.mounted)
            assertSame(registered, bridge.call)
            assertTrue(bridge.belongsTo(registered, bridge.frame))
            assertTrue(registered.activated)
            assertFalse(registered.retired)
            assertFalse(bridge.canHandle(request, surface))
            assertFalse(bridge.register(call()))

            bridge.frame = owner
            assertSame(registered, bridge.call)
            assertFalse(bridge.register(call()))
        }
    }

    @Test fun ownerUsesInstanceIdentityAndAccountInsteadOfInstanceEquality() {
        // 两个值相等的运行实例仍代表不同的包生命周期。
        data class EqualInstance(val identity: String)
        val previous = EqualInstance("same-value")
        val replacement = EqualInstance("same-value")
        assertEquals(previous, replacement)
        assertNotSame(previous, replacement)
        val owner = SourcePublicRootSearchFrame("kugou", previous, "account-a", canStart = true)
        val bridge = SourcePublicRootSearchBridge().apply { frame = owner }
        val candidate = SourcePublicRootSearchCall(SourcePageRequest.Search("kugou"), owner)

        assertTrue(bridge.belongsTo(candidate, owner.copy(canStart = false)))
        assertFalse(bridge.belongsTo(candidate, owner.copy(instance = replacement)))
        assertFalse(bridge.belongsTo(candidate, owner.copy(accountId = "account-b")))
        assertFalse(bridge.belongsTo(candidate, owner.copy(accountId = null)))
        assertFalse(bridge.belongsTo(candidate, owner.copy(sourceId = "qq")))
        assertFalse(bridge.belongsTo(candidate, null))

        bridge.frame = owner.copy(instance = replacement)
        assertFalse(bridge.register(candidate))
        bridge.frame = owner.copy(accountId = "account-b")
        assertFalse(bridge.register(candidate))
        bridge.frame = owner
        assertTrue(bridge.register(candidate))
    }

    @Test fun detachingAnOldCompositionCannotRetireANewerCall() {
        val fixture = Fixture()
        with(fixture) {
            val previous = call()
            var previousNotifications = 0
            previous.onClose = { previousNotifications++ }
            assertTrue(bridge.register(previous))
            bridge.retire(previous, notify = false)
            val current = call()
            assertTrue(bridge.register(current))

            bridge.retire(previous, notify = false)
            bridge.retire(previous, notify = true)

            assertSame(current, bridge.call)
            assertFalse(current.retired)
            assertEquals(0, previousNotifications)
        }
    }

    @Test fun retiringReleasesOwnershipBeforeNotifyingAndNotifiesOnlyOnce() {
        val fixture = Fixture()
        with(fixture) {
            val previous = call()
            val next = call()
            var notifications = 0
            previous.onClose = {
                notifications++
                assertTrue(previous.retired)
                assertNull(bridge.call)
                assertFalse(bridge.mounted)
                assertTrue(bridge.register(next))
            }
            assertTrue(bridge.register(previous))

            bridge.retire(previous, notify = true)
            bridge.retire(previous, notify = true)

            assertEquals(1, notifications)
            assertSame(next, bridge.call)
            assertTrue(bridge.mounted)
            assertFalse(next.retired)
        }
    }

    @Test fun aRetiredCallCannotBeRegisteredAgainAfterItsSlotIsFree() {
        val fixture = Fixture()
        with(fixture) {
            val previous = call()
            assertTrue(bridge.register(previous))
            bridge.retire(previous, notify = false)

            assertTrue(previous.retired)
            assertNull(bridge.call)
            assertFalse(bridge.register(previous))
            assertNull(bridge.call)
            assertTrue(bridge.register(call()))
        }
    }

    @Test fun expiredAuthenticationInvalidatesSearchEvenWhenTheAccountObjectIsRetained() {
        val owner = SourcePublicRootSearchFrame("kugou", Any(), "account-a", true, SourceSessionStatus.AUTHENTICATED)
        val candidate = SourcePublicRootSearchCall(SourcePageRequest.Search("kugou"), owner)
        val bridge = SourcePublicRootSearchBridge().apply { frame = owner }
        assertTrue(bridge.register(candidate))
        assertFalse(bridge.belongsTo(candidate, owner.copy(sessionStatus = SourceSessionStatus.EXPIRED)))
        assertFalse(bridge.belongsTo(candidate, owner.copy(sessionStatus = SourceSessionStatus.GUEST)))
        assertTrue(bridge.belongsTo(candidate, owner.copy(canStart = false)))
    }
}
