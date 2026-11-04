package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QqAccountRevisionTest {
    @Test fun sameAccountRenewalPreservesLeaseVersionButLoginAndLogoutInvalidateIt() {
        val account = MusicAccount(MusicSource.QQ, "account-one", "账号", null)
        val before = PlatformSession(MusicSource.QQ, "old-ticket", "device", account)
        val renewal = QqPersistentSession(refresh = { _, _ -> "renewed-ticket" }, account = { account })
            .validate(before.copy(credential = "old-ticket; musickeyCreateTime=1; keyExpiresIn=1"), nowSeconds = 100)
        val revision = QqAccountRevision()
        val captured = revision.get()
        assertNotEquals(before.credential, renewal.credential)
        revision.saved(before.account, renewal.account.copy(nickname = "更新昵称"), refreshing = true, wasExpired = false)
        assertEquals(captured, revision.get())
        revision.saved(account, account.copy(userId = "account-two"), refreshing = true, wasExpired = false)
        assertNotEquals(captured, revision.get())
        val afterSwitch = revision.get()
        revision.saved(account, account, refreshing = false, wasExpired = false)
        assertEquals(afterSwitch + 1, revision.get())
        val beforeLogout = revision.get()
        revision.incrementAndGet()
        assertEquals(beforeLogout + 1, revision.get())
        val expired = revision.get()
        revision.saved(account, account, refreshing = true, wasExpired = true)
        assertEquals(expired + 1, revision.get())
    }
}
