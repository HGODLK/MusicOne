package com.musicone.source.runtime

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceArchiveVerifierTest {
    @Test fun unsignedExecutableIsRejectedEvenWithSourceExtension() {
        val file = File.createTempFile("unsigned-source", ".mscm")
        try {
            JarOutputStream(file.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("classes.dex"))
                jar.write("not-a-signed-dex".toByteArray())
                jar.closeEntry()
            }
            assertThrows(IllegalStateException::class.java) { SourceArchiveVerifier(37).verify(file) }
        } finally { file.delete() }
    }

    @Test fun archivePathCannotEscapePackageDirectory() {
        val file = File.createTempFile("traversal-source", ".mscp")
        try {
            JarOutputStream(file.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("../classes.dex"))
                jar.write(byteArrayOf(1))
                jar.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { SourceArchiveVerifier(37).verify(file) }
        } finally { file.delete() }
    }
}
