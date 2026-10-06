package com.musicone.demo

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object NeteaseCrypto {
    private const val LINUX_KEY_HEX = "7246674226682325323F5E6544673A51"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private val secureRandom = SecureRandom()

    fun encryptLinux(payload: String): String =
        aesEcb(payload.toByteArray(Charsets.UTF_8), hexToBytes(LINUX_KEY_HEX), Cipher.ENCRYPT_MODE)
            .toHex(upperCase = true)

    fun encryptEapi(apiPath: String, payload: String): String {
        val parsedPath = runCatching { URI.create(apiPath).path }.getOrNull().orEmpty()
        val normalizedPath = (parsedPath.ifBlank { apiPath }).replace("/eapi/", "/api/")
        val digestInput = "nobody${normalizedPath}use${payload}md5forencrypt"
        val digest = MessageDigest.getInstance("MD5").digest(digestInput.toByteArray(Charsets.UTF_8)).toHex()
        val message = "$normalizedPath-36cd479b6b5-$payload-36cd479b6b5-$digest"
        return aesEcb(message.toByteArray(Charsets.UTF_8), EAPI_KEY.toByteArray(), Cipher.ENCRYPT_MODE).toHex()
    }

    fun decryptMobileEapi(body: ByteArray): String {
        if (body.looksLikeJson()) return body.toString(Charsets.UTF_8)
        val compressed = body.gunzipIfNeeded()
        val decrypted = aesEcb(compressed, EAPI_KEY.toByteArray(), Cipher.DECRYPT_MODE, padding = false)
            .removePkcs7Padding()
            .gunzipIfNeeded()
        if (!decrypted.looksLikeJson()) throw NeteaseApiException("网易云返回了无法识别的数据")
        return decrypted.toString(Charsets.UTF_8)
    }

    fun randomDeviceId(): String = ByteArray(26).also(secureRandom::nextBytes).toHex(upperCase = true)

    private fun aesEcb(data: ByteArray, key: ByteArray, mode: Int, padding: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (padding) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

}

private fun ByteArray.looksLikeJson(): Boolean {
    val first = firstOrNull { byte -> !byte.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
    return first == '{' || first == '['
}

private fun ByteArray.gunzipIfNeeded(): ByteArray {
    if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
    return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
}

private fun ByteArray.removePkcs7Padding(): ByteArray {
    if (isEmpty()) return this
    val padding = last().toInt() and 0xff
    if (padding !in 1..16 || padding > size) return this
    if (takeLast(padding).any { (it.toInt() and 0xff) != padding }) return this
    return copyOf(size - padding)
}

private fun ByteArray.toHex(upperCase: Boolean = false): String {
    val alphabet = if (upperCase) "0123456789ABCDEF" else "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

private fun hexToBytes(value: String): ByteArray =
    ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
