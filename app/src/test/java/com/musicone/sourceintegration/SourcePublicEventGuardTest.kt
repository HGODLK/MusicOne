package com.musicone.sourceintegration

import org.junit.Assert.*
import org.junit.Test

class SourcePublicEventGuardTest {
    private data class Account(val status: String, val id: String, val revision: Long)

    @Test fun currentAccountReceivesClicksAndValues() {
        val guard = SourcePublicEventGuard({}, { Account("connected", "one", 1) })
        var clicks = 0
        var selected = ""
        guard.event { clicks++ }()
        guard.valueEvent<String> { selected = it }("track-one")
        assertEquals(1, clicks)
        assertEquals("track-one", selected)
    }

    @Test fun switchingAccountDropsOldEventsButNewCompositionWorks() {
        var account = Account("connected", "one", 1)
        val guard = SourcePublicEventGuard({}, { account })
        val received = mutableListOf<String>()
        val oldClick = guard.event { received += "old-click" }
        val oldSelection = guard.valueEvent<String> { received += it }
        account = Account("connected", "two", 1)
        oldClick()
        oldSelection("old-track")
        guard.event { received += "new-click" }()
        guard.valueEvent<String> { received += it }("new-track")
        assertEquals(listOf("new-click", "new-track"), received)
    }

    @Test fun sameAccountNewSessionInvalidatesOldEvent() {
        var account = Account("connected", "one", 1)
        val guard = SourcePublicEventGuard({}, { account })
        var clicks = 0
        val event = guard.event { clicks++ }
        account = account.copy(revision = 2)
        event()
        assertEquals(0, clicks)
    }

    @Test fun expiredStatusInvalidatesOldValueEvent() {
        var account = Account("connected", "one", 1)
        val guard = SourcePublicEventGuard({}, { account })
        var selected: String? = null
        val event = guard.valueEvent<String> { selected = it }
        account = account.copy(status = "expired")
        event("old-track")
        assertNull(selected)
    }

    @Test fun removedInstanceCannotRunCapturedEvents() {
        var active = true
        val guard = SourcePublicEventGuard({ check(active) }, { null })
        var calls = 0
        val click = guard.event { calls++ }
        val selection = guard.valueEvent<String> { calls++ }
        active = false
        assertThrows(IllegalStateException::class.java) { click() }
        assertThrows(IllegalStateException::class.java) { selection("old-track") }
        assertEquals(0, calls)
    }
}
