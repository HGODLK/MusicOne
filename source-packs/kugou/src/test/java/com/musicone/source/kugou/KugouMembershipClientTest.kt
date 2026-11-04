package com.musicone.source.kugou

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouMembershipClientTest {
    private fun response(fields: String) = JSONObject("""{"status":1,"error_code":0,"data":{$fields}}""")

    @Test fun readsMusicPackageInsteadOfGeneralVipIdentity() {
        assertEquals(false, parseKugouMembership(response("\"vip_type\":0,\"m_type\":0")))
        assertEquals(false, parseKugouMembership(response("\"vip_type\":5,\"m_type\":0,\"y_type\":1,\"is_vip\":1")))
        for (type in listOf(1, 2, 3, 4, 6)) {
            assertEquals(false, parseKugouMembership(response("\"vip_type\":$type,\"m_type\":0")))
            assertNull(parseKugouMembership(response("\"vip_type\":$type")))
        }
        for (type in 1..4) assertEquals(true, parseKugouMembership(response("\"m_type\":$type")))
        assertNull(parseKugouMembership(response("\"vip_type\":99,\"m_type\":99")))
    }

    @Test fun membershipIdentityBitsCannotGrantAllSongAccess() {
        for (type in listOf(1, 8, 16, 24)) {
            assertNull(parseKugouMembership(response("\"user_type\":$type")))
            assertEquals(false, parseKugouMembership(response("\"user_type\":$type,\"m_type\":0")))
        }
        assertNull(parseKugouMembership(response("\"user_type\":-1")))
        assertNull(parseKugouMembership(response("\"y_type\":1,\"is_vip\":1")))
    }

    @Test fun confirmedChangtingRightAlsoGrantsOrdinaryMusicAccess() {
        assertEquals(true, parseKugouMembership(response("\"user_type\":256")))
        assertEquals(true, parseKugouMembership(response("\"m_type\":0,\"user_type\":280")))
        assertNull(parseKugouMembership(response("\"m_type\":99,\"user_type\":-1")))
    }

    @Test fun failureAndMissingFieldsRemainUnknown() {
        assertNull(parseKugouMembership(JSONObject("""{"status":0,"error_code":20010,"data":{"vip_type":1}}""")))
        assertNull(parseKugouMembership(JSONObject("""{"status":1,"data":{}}""")))
    }

    @Test fun cachesOnlyCurrentCredentialAndRefreshesRealChanges() {
        var time = 1_000L
        var calls = 0
        var music = 1
        val client = KugouMembershipClient(load = { calls++; response("\"m_type\":$music") }, now = { time })
        assertTrue(client.hasVipAccess("first"))
        music = 0
        assertTrue(client.hasVipAccess("first"))
        assertEquals(1, calls)
        assertNull(client.cached("second"))
        assertFalse(client.hasVipAccess("second"))
        time += 5 * 60_000L
        assertNull(client.cached("second"))
        assertFalse(client.hasVipAccess("second"))
        assertEquals(3, calls)
    }

    @Test fun failedRefreshDoesNotReuseExpiredOrHistoricalMemberTruth() {
        var time = 1_000L
        var fail = false
        val client = KugouMembershipClient(load = {
            if (fail) throw java.io.IOException("offline") else response("\"m_type\":1")
        }, now = { time })
        assertTrue(client.hasVipAccess("account"))
        fail = true
        time += 5 * 60_000L
        assertFalse(client.hasVipAccess("account"))
        assertNull(client.cached("account"))
        client.clear()
        assertNull(client.cached("account"))
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotConvertedToNonMember() {
        KugouMembershipClient(load = { throw CancellationException() }).hasVipAccess("account")
    }

    @Test fun detectionPreservesFailureAndConfirmedNonMemberSeparately() {
        val failed = KugouMembershipClient(load = { throw java.io.IOException("offline") })
        assertNull(failed.detectMembership("account"))
        assertNull(failed.cached("account"))
        val nonMember = KugouMembershipClient(load = { response("\"vip_type\":1,\"m_type\":0") })
        assertEquals(false, nonMember.detectMembership("account"))
        assertEquals(false, nonMember.cached("account"))
    }

    @Test fun logoutDuringQueryCannotPublishTheOldMembership() {
        lateinit var client: KugouMembershipClient
        client = KugouMembershipClient(load = { client.clear(); response("\"m_type\":1") })
        assertNull(client.detectMembership("account"))
        assertNull(client.cached("account"))
    }

    @Test fun requestUsesExistingIdentityAndEncryptedTokenPayload() {
        val session = KugouSession("", "test-token", "123", "mid", "dfid")
        val body = kugouMembershipBody(session, 123456L)
        assertEquals(123L, body.getLong("userid"))
        assertEquals(1, body.getInt("usertype"))
        assertEquals(123456L, body.getLong("visit_time"))
        // 期望明文与实现使用同一序列化器：JVM 的 JSONObject 不保证字面量里的 key 顺序。
        val plain = JSONObject().put("token", "test-token").put("clienttime", 123456L).toString()
        assertTrue(plain.contains("test-token"))
        assertEquals(KugouLoginCrypto.rawRsaEncrypt(plain), body.getString("p"))
        assertFalse(body.toString().contains("test-token"))
    }
}
