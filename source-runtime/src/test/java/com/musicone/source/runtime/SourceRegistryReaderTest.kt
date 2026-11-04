package com.musicone.source.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SourceRegistryReaderTest {
    @Test fun descriptorMetadataSurvivesWithoutAnActivatedInstance() {
        val result = SourceRegistryReader.read { """{
            "org.example.source":{"signer":"identity","active":null,"pending":3,"rollback":null,
            "removed":false,"kind":"SUPPLEMENTAL","name":"独立补充源","versionName":null,"pendingVersionName":"测试三"}
        }""" }
        val source = result.states.getValue("org.example.source")
        assertEquals(com.musicone.source.sdk.SourceKind.SUPPLEMENTAL, source.kind)
        assertEquals("独立补充源", source.name)
        assertEquals("测试三", source.pendingVersionName)
        assertNull(source.versionName)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun corruptRegistryDoesNotThrowOrOverwriteOriginal() {
        val file = Files.createTempFile("source-registry", ".json").toFile()
        try {
            file.writeText("{interrupted")
            val result = SourceRegistryReader.read { file.readText() }
            assertTrue(result.states.isEmpty())
            assertTrue(result.errors.isNotEmpty())
            assertEquals("{interrupted", file.readText())
        } finally { file.delete() }
    }
    @Test fun malformedSourceDoesNotPreventOtherSourceLoading() {
        val result = SourceRegistryReader.read { """{
            "org.example.valid":{"signer":"identity","active":2,"pending":null,"rollback":1,"removed":false},
            "org.example.invalid":{"active":"broken"}
        }""" }
        assertEquals(setOf("org.example.valid"), result.states.keys)
        assertEquals(2L, result.states.getValue("org.example.valid").activeVersion)
        assertNull(result.states.getValue("org.example.valid").kind)
        assertNull(result.states.getValue("org.example.valid").name)
        assertEquals(1, result.errors.size)
    }
    @Test fun legacyFilesKeepNumericIdentityAndUnsafeInstallIdsAreRejected() {
        val original = """{"org.example.source":{"signer":"identity","active":4,"pending":null,"rollback":3,"removed":false}}"""
        val state = SourceRegistryReader.read { original }.states.getValue("org.example.source")
        assertEquals(SourceInstallRef(4, "4"), state.activePackage)
        assertEquals(SourceInstallRef(3, "3"), state.rollbackPackage)
        val invalid = original.replace("\"active\":4", "\"active\":4,\"activeInstallId\":\"../other\"")
        assertTrue(SourceRegistryReader.read { invalid }.errors.isNotEmpty())
        assertTrue(SourceRegistryReader.read { original.replace("\"active\":4", "\"active\":0") }.errors.isNotEmpty())
    }
}
