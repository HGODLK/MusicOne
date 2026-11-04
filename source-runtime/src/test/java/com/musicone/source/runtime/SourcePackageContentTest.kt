package com.musicone.source.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SourcePackageContentTest {
    @Test fun retainedRollbackPackageIsOnlyReusedForIdenticalContent() {
        val directory = Files.createTempDirectory("source-package-reuse").toFile()
        try {
            val retained = directory.resolve("retained.mscp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val incoming = directory.resolve("incoming.mscp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(sameSourcePackageContent(incoming, retained))
            incoming.writeBytes(byteArrayOf(3, 2, 1))
            assertFalse(sameSourcePackageContent(incoming, retained))
            incoming.writeBytes(byteArrayOf(1, 2))
            assertFalse(sameSourcePackageContent(incoming, retained))
            assertArrayEquals(byteArrayOf(1, 2, 3), retained.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
