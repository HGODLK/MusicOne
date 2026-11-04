package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSessionRevisionTest {
    private val account = MusicAccount(MusicSource.QQ, "account-one", "原昵称", null)
    private val session = PlatformSession(MusicSource.QQ, "source-session:qq:account-one:0", "", account)

    @Test fun repeatedAccountReadsAndSuccessfulValidationKeepHomepageContent() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        repeat(4) { assertEquals(initial, revision.update(session.copy(), true)) }
    }

    @Test fun profileChangesDoNotReplayHomepageEntrance() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        val updated = account.copy(nickname = "新昵称", avatarUrl = "https://example.com/avatar.jpg",
            hasVipAccess = true, backgroundUrl = "https://example.com/background.jpg")
        assertEquals(initial, revision.update(session.copy(account = updated), true))
    }

    @Test fun reloginWithTheSameAccountStillInvalidatesHomepage() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        assertTrue(revision.update(session.copy(credential = "source-session:qq:account-one:1"), true) > initial)
    }

    @Test fun logoutAndAccountSwitchStillInvalidateHomepage() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        val loggedOut = revision.update(session.copy(credential = "", account = null), true)
        assertTrue(loggedOut > initial)
        val switched = session.copy(credential = "source-session:qq:account-two:0",
            account = account.copy(userId = "account-two"))
        assertTrue(revision.update(switched, true) > loggedOut)
    }

    @Test fun sourceSwitchAndReturnStillInvalidateHomepage() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        val local = revision.update(PlatformSession(MusicSource.LOCAL, "", "", null), true)
        assertTrue(local > initial)
        assertTrue(revision.update(session, true) > local)
    }

    @Test fun removalAndRestoreStillInvalidateHomepage() {
        val revision = HomeSessionRevision()
        val initial = revision.update(session, true)
        val removed = revision.update(session, false)
        assertTrue(removed > initial)
        assertEquals(removed, revision.update(session.copy(credential = "unused"), false))
        assertTrue(revision.update(session, true) > removed)
    }
}
