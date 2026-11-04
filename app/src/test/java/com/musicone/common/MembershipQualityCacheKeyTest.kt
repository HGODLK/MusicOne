package com.musicone

import org.junit.Assert.*
import org.junit.Test

class MembershipQualityCacheKeyTest {
    @Test fun declaredUnknownIsSeparateFromUndeclaredAndConfirmedMembership() {
        fun key(member: Boolean?) = playbackQualityCacheKey(MusicSource.QQ, "qq-1", "QQ:42", member)
        assertNotEquals(key(false), key(null))
        assertNotEquals(key(true), key(null))
        assertNotEquals(key(false), key(true))
        assertTrue(key(false).endsWith("|plain"))
        assertTrue(key(true).endsWith("|vip"))
        assertTrue(key(null).endsWith("|unknown"))
    }
}
