package com.musicone.source.netease

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseMembershipDetectionTest {
    @Test fun musicPackageAndBlackVinylConfirmMusicMembership() {
        for (type in listOf(10, 11)) {
            assertEquals(true, JSONObject("""{"profile":{"vipType":$type}}""").neteaseConfirmedMusicMembership())
            assertEquals(true, JSONObject("""{"account":{"vipType":"$type"}}""").neteaseConfirmedMusicMembership())
        }
    }

    @Test fun nonMemberMissingAndUnrecognizedTypesRemainUnknown() {
        for (type in listOf(0, -1, 1, 2, 12)) {
            assertNull(JSONObject("""{"profile":{"vipType":$type}}""").neteaseConfirmedMusicMembership())
        }
        assertNull(JSONObject("""{"profile":{}}""").neteaseConfirmedMusicMembership())
        assertNull(JSONObject("""{"profile":{"vipType":"invalid"}}""").neteaseConfirmedMusicMembership())
    }

    @Test fun accountMappingKeepsConfirmedStatusSeparateFromOldDisplayFlags() {
        val member = JSONObject("""{"profile":{"userId":42,"vipType":11}}""").toMusicAccount()
        assertEquals(true, member.confirmedMusicMembership)
        val unrecognized = JSONObject("""{"profile":{"userId":42,"vipType":1}}""").toMusicAccount()
        assertTrue(unrecognized.hasVipAccess)
        assertNull(unrecognized.confirmedMusicMembership)
        assertNull(NeteaseAccount(userId = "42", nickname = "用户", avatarUrl = null, hasVipAccess = true).confirmedMusicMembership)
    }
}
