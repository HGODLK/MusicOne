package com.musicone

import org.junit.Assert.*
import org.junit.Test

class AiCompatibleEndpointTest {
    @Test fun deepSeekSearchNeverUsesCustomChatEndpoint() {
        val custom = "https://private.example:8443/custom/v1/"
        assertEquals("https://api.deepseek.com", aiProviderEndpoint(AiProvider.DEEPSEEK, custom))
        assertEquals("https://private.example:8443/custom/v1", aiProviderEndpoint(AiProvider.OTHER, custom))
        assertEquals("https://api.deepseek.com", aiProviderEndpoint(AiProvider.DEEPSEEK, ""))
    }

    @Test fun customDomainPortAndPathArePreserved() {
        assertEquals("https://example.org/custom/api/v1", aiNormalizeBaseUrl(" https://example.org/custom/api/v1/ "))
        assertEquals("http://192.168.1.10:8080/v1", aiNormalizeBaseUrl("http://192.168.1.10:8080/v1"))
        assertEquals("http://localhost:8080", aiNormalizeBaseUrl("http://localhost:8080/"))
    }

    @Test fun malformedOrCredentialBearingAddressesAreRejected() {
        listOf("", "example.org/v1", "ftp://example.org/v1", "https://key@example.org/v1", "https://example.org/v1?key=secret", "https://example.org/#fragment")
            .forEach { value -> try { aiNormalizeBaseUrl(value); fail(value) } catch (_: IllegalArgumentException) { } }
    }

    @Test fun legacyMiMoMigratesWithoutDecryptingKeyAndRequiresVerification() {
        val old = mapOf("provider" to "MIMO", "key_MIMO" to "encrypted", "model_MIMO" to "mimo-v2.6-pro",
            "models_MIMO" to "[]", "verified_MIMO" to true)
        val migrated = aiMigrateProviderSettings(old)
        assertEquals("OTHER", migrated["provider"])
        assertEquals("encrypted", migrated["key_OTHER"])
        assertEquals("mimo-v2.6-pro", migrated["model_OTHER"])
        assertEquals("https://api.xiaomimimo.com/v1", migrated["base_url_OTHER"])
        assertEquals(false, migrated["verified_OTHER"])
        assertTrue(aiMigrateProviderSettings(old + migrated).isEmpty())
    }

    @Test fun migrationDoesNotOverwriteExistingCustomServiceOrDeepSeek() {
        val old = mapOf("provider" to "DEEPSEEK", "key_DEEPSEEK" to "deepseek", "key_MIMO" to "old",
            "key_OTHER" to "custom", "base_url_OTHER" to "https://custom.org/v1", "verified_OTHER" to true)
        val migrated = aiMigrateProviderSettings(old)
        assertFalse(migrated.containsKey("provider"))
        assertFalse(migrated.containsKey("key_OTHER"))
        assertFalse(migrated.containsKey("base_url_OTHER"))
        assertFalse(migrated.containsKey("verified_OTHER"))
        assertFalse(migrated.containsKey("key_DEEPSEEK"))
        assertTrue(aiMigrateProviderSettings(mapOf("provider" to "DEEPSEEK")).isEmpty())
    }
}
