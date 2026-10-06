package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformSmsLoginTest {
    @Test
    fun securityVerificationKeepsSessionAndReplacesUpdatedCookies() {
        assertEquals(
            "device=one; session=new; verify=passed",
            mergePlatformCredentials(
                "device=one; session=old",
                "verify=passed; session=new",
            ),
        )
    }

    @Test
    fun kugouCredentialEncryptionMatchesProtocolVector() {
        val source = "{\"mobile\":\"13800138000\",\"code\":\"123456\"}"
        val encrypted = KugouLoginCrypto.encryptCredential(source, "abc123def456ghij")

        assertEquals(
            "8ddaf7d6d5161331bed62dd3a61778ffffd634bc9551028997ea656914aaebea73892197e01a8754caac2379dc3abc6b",
            encrypted,
        )
        assertEquals(source, KugouLoginCrypto.decryptCredential(encrypted, "abc123def456ghij"))
    }

    @Test
    fun kugouRawRsaAndLoginKeyMatchProtocolVectors() {
        val payload = "{\"clienttime_ms\":1720000000000,\"key\":\"abc123def456ghij\"}"

        assertEquals(
            "B916838DBE1D6424F4FFD3E6032DF9732739F4E4B591EBC2D561F177984F99B126A320E0F9A2147EC099FE218E006693D2E8EDBCB58E3547FB51D3A4E6D2689489FAA7930B460E39DFE2BC4897E9F1C96279406CB7E930542578DDFECE510A09BE9C85576560679E29BE5BEDF359E9918BB11E2C6D9FB9753403AE7902125467",
            KugouLoginCrypto.rawRsaEncrypt(payload),
        )
        assertEquals("186d163d2b7c15a88e1755f7a7cd2e16", KugouProtocol.loginKey(1_720_000_000_000L))
    }
}
