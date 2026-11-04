package com.musicone.source.kugou

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouSourceManifestTest {
    @Test fun kugouRequiresLoginAndKeepsTheExistingAuthenticationAndOptionalListeningProvider() {
        val entry = KugouSourceEntry()
        assertTrue(entry.descriptor.requiresLogin)
        assertNotNull(entry.authentication)
        assertNotNull(entry.authenticationPage)
        assertTrue(entry.mediaPlayback is com.musicone.source.sdk.SourcePlaybackSessionProvider)
    }

    @Test fun customRootKeepsGuestsExpiredAndIncompleteAccountsOnAuthentication() {
        val account = com.musicone.source.sdk.SourceAccount("42", "测试账号")
        assertTrue(kugouRootNeedsLogin(com.musicone.source.sdk.SourceAccountState(com.musicone.source.sdk.SourceSessionStatus.GUEST, null, 0)))
        assertTrue(kugouRootNeedsLogin(com.musicone.source.sdk.SourceAccountState(com.musicone.source.sdk.SourceSessionStatus.EXPIRED, account, 1)))
        assertTrue(kugouRootNeedsLogin(com.musicone.source.sdk.SourceAccountState(com.musicone.source.sdk.SourceSessionStatus.AUTHENTICATED, null, 2)))
        assertFalse(kugouRootNeedsLogin(com.musicone.source.sdk.SourceAccountState(com.musicone.source.sdk.SourceSessionStatus.AUTHENTICATED, account, 3)))
    }

    @Test fun savedQueueAndDirectBusinessRequestsRequireTheSameCompleteAccount() {
        val cookie = "userid=42; token=test-token; KUGOU_API_MID=mid"
        assertEquals("42", kugouAuthenticatedAccountId(cookie, "42"))
        requireKugouAuthenticatedRequest(cookie, "42")
        for ((credential, accountId) in listOf("" to null, cookie to null, cookie to "43",
            "userid=42; KUGOU_API_MID=mid" to "42")) {
            assertNull(kugouAuthenticatedAccountId(credential, accountId))
            try { requireKugouAuthenticatedRequest(credential, accountId); fail("游客或不完整账号不能直接取源") }
            catch (_: com.musicone.source.sdk.SourceOperationException) { }
        }
    }

    @Test fun kugouDoesNotExposeRemovedLibraryActions() {
        val entry = KugouSourceEntry()
        assertFalse(entry.entities is com.musicone.source.sdk.SourceEntityFollowing)
        assertFalse(entry.collectionEditing is com.musicone.source.sdk.SourceCollectionRemoteOrder)
        assertFalse(entry.collectionEditing is com.musicone.source.sdk.SourceCollectionDetailsEditing)
        assertFalse(entry.search is com.musicone.source.sdk.SourceHotSearch)
    }

    @Test fun realEntryMatchesTheDistributedManifest() {
        val json = javaClass.classLoader!!.getResourceAsStream("musicone-source.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val entry = KugouSourceEntry()
        val actual = entry.descriptor
        assertEquals(json.getString("entryClass"), entry.javaClass.name)
        assertEquals(json.getString("id"), actual.id)
        assertEquals(json.getString("name"), actual.name)
        assertEquals(json.getString("kind"), actual.kind.name)
        assertEquals(json.getLong("versionCode"), actual.versionCode)
        assertEquals(json.getString("versionName"), actual.versionName)
        assertEquals(System.getProperty("musicone.kugou.versionCode").toLong(), actual.versionCode)
        assertEquals(System.getProperty("musicone.kugou.versionName"), actual.versionName)
        assertEquals(json.getBoolean("useCustomPages"), actual.useCustomPages)
        assertEquals(json.getBoolean("requiresLogin"), actual.requiresLogin)
        val capabilities = json.getJSONArray("capabilities")
        assertEquals((0 until capabilities.length()).map { capabilities.getString(it) }.toSet(),
            actual.capabilities.map { it.name }.toSet())
        val permissions = json.getJSONObject("permissions")
        assertEquals(permissions.getBoolean("editTrackMetadata"), actual.permissions.editTrackMetadata)
        assertEquals(permissions.getBoolean("replaceManualLyrics"), actual.permissions.replaceManualLyrics)
        assertEquals(permissions.getBoolean("adjustLineOffset"), actual.permissions.adjustLineOffset)
        val presentation = json.getJSONObject("presentation")
        assertEquals(presentation.getLong("pageColorArgb"), actual.presentation.pageColorArgb)
        assertEquals(presentation.getLong("lineLeadMs"), actual.presentation.lineLeadMs)
        assertEquals(presentation.getLong("wordLeadMs"), actual.presentation.wordLeadMs)
    }
}
