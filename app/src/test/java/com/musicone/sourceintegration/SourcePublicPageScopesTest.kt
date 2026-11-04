package com.musicone.sourceintegration

import org.junit.Assert.*
import org.junit.Test

class SourcePublicPageScopesTest {
    private data class Scope(val key: String)

    @Test
    fun requestAndSourceSwitchKeepEachSourcesOwnScope() {
        val discarded = mutableListOf<Scope>()
        val scopes = SourcePublicPageScopes(create = { _, key -> Scope(key) }, discard = { discarded += it })
        val firstInstance = Any()
        val secondInstance = Any()
        val first = scopes.obtain("first", firstInstance, "account:a")
        val second = scopes.obtain("second", secondInstance, "account:b")
        assertSame(first, scopes.obtain("first", firstInstance, "account:a"))
        assertSame(second, scopes.obtain("second", secondInstance, "account:b"))
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun accountChangeLogoutExpiryAndUpgradeDiscardPreviousScopes() {
        val discarded = mutableListOf<Scope>()
        val scopes = SourcePublicPageScopes(create = { _, key -> Scope(key) }, discard = { discarded += it })
        val instance = Any()
        val first = scopes.obtain("source", instance, "account:a")
        val changed = scopes.obtain("source", instance, "account:b")
        val guest = scopes.obtain("source", instance, "guest:GUEST")
        val expired = scopes.obtain("source", instance, "guest:EXPIRED")
        val upgraded = scopes.obtain("source", Any(), "guest:EXPIRED")
        assertEquals(listOf(first, changed, guest, expired), discarded)
        assertEquals(5, (discarded + upgraded).map { it.key }.distinct().size)
    }

    @Test
    fun inactiveSourceSessionChangesAreDiscardedWithoutTouchingOtherSources() {
        val discarded = mutableListOf<Scope>()
        val scopes = SourcePublicPageScopes(create = { _, key -> Scope(key) }, discard = { discarded += it })
        val instance = Any()
        val first = scopes.obtain("first", instance, "account:a")
        val second = scopes.obtain("second", instance, "account:b")
        scopes.reconcile { source -> instance to if (source == "first") "guest:GUEST" else "account:b" }
        assertEquals(listOf(first), discarded)
        assertSame(second, scopes.obtain("second", instance, "account:b"))
        scopes.reconcile { null }
        assertEquals(listOf(first, second), discarded)
    }
}
