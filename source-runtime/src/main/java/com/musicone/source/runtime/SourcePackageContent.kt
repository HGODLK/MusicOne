package com.musicone.source.runtime

import java.io.File
import java.security.MessageDigest

/** 内容相同才复用已有安装；同版本不同内容使用独立安装文件。 */
internal fun sameSourcePackageContent(incoming: File, retained: File): Boolean {
    if (incoming.length() != retained.length()) return false
    fun digest(file: File): ByteArray {
        val result = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                result.update(buffer, 0, size)
            }
        }
        return result.digest()
    }
    return digest(incoming).contentEquals(digest(retained))
}
