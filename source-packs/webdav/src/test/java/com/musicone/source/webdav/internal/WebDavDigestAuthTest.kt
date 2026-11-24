package com.musicone.source.webdav.internal

import org.junit.Assert.*
import org.junit.Test

class WebDavDigestAuthTest {
    @Test
    fun computesStandardRfc2617DigestResponse() {
        val header = """Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41""""
        val challenge = WebDavAuthManager.parseDigestChallenge(header)
        assertNotNull(challenge)
        assertEquals("testrealm@host.com", challenge!!.realm)
        assertEquals("auth", challenge.qop)
        assertEquals("MD5", challenge.algorithm)

        // RFC 2617 Section 3.5 官方标准测试向量
        val auth = WebDavAuthManager.computeDigestAuthorization(
            username = "Mufasa",
            password = "Circle Of Life",
            method = "GET",
            uri = "/dir/index.html",
            challenge = challenge,
            nc = "00000001",
            cnonce = "0a4f113b",
        )
        assertTrue(auth.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(auth.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
        assertTrue(auth.contains("nc=00000001"))
    }

    @Test
    fun buildsBasicHeaderAndPrefersDigestInMultipleChallenges() {
        assertEquals("Basic YWRtaW46c2VjcmV0", WebDavAuthManager.buildBasicHeader("admin", "secret"))

        val headers = listOf(
            """Basic realm="dav"""",
            """Digest realm="dav", nonce="abc123", algorithm=SHA-256, qop="auth"""",
        )
        val foundDigest = WebDavAuthManager.findChallengeHeader(headers, "Digest")
        assertNotNull(foundDigest)
        val parsed = WebDavAuthManager.parseDigestChallenge(foundDigest!!)
        assertEquals("SHA-256", parsed?.algorithm)
    }
}
