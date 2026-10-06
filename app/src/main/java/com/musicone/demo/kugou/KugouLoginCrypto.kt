package com.musicone.demo

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class KugouEncryptedPayload(
    val key: String,
    val ciphertext: String,
)

internal object KugouLoginCrypto {
    private val secureRandom = SecureRandom()
    private val rsaModulus = BigInteger(
        "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af767" +
            "9b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525" +
            "d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724" +
            "336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b",
        16,
    )
    private val rsaExponent = BigInteger.valueOf(65_537L)

    fun encryptCredential(value: String): KugouEncryptedPayload {
        val key = randomLowercaseKey(16)
        return KugouEncryptedPayload(key, encryptCredential(value, key))
    }

    fun encryptCredential(value: String, temporaryKey: String): String {
        val key = KugouProtocol.md5(temporaryKey)
        return aes(Cipher.ENCRYPT_MODE, value.toByteArray(), key, key.takeLast(16)).toHex()
    }

    fun decryptCredential(value: String, temporaryKey: String): String {
        val key = KugouProtocol.md5(temporaryKey)
        return aes(Cipher.DECRYPT_MODE, value.hexBytes(), key, key.takeLast(16)).toString(Charsets.UTF_8)
    }

    fun encryptFixed(value: String, key: String, iv: String): String =
        aes(Cipher.ENCRYPT_MODE, value.toByteArray(), key, iv).toHex()

    fun rawRsaEncrypt(value: String): String {
        val source = value.toByteArray(Charsets.UTF_8)
        require(source.size <= RSA_BYTES) { "酷狗音乐登录参数过长" }
        val padded = ByteArray(RSA_BYTES)
        source.copyInto(padded)
        return BigInteger(1, padded).modPow(rsaExponent, rsaModulus)
            .toString(16)
            .padStart(RSA_BYTES * 2, '0')
            .uppercase()
    }

    private fun aes(mode: Int, value: ByteArray, key: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray()))
        return cipher.doFinal(value)
    }

    private fun randomLowercaseKey(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) { repeat(length) { append(chars[secureRandom.nextInt(chars.length)]) } }
    }

    private const val RSA_BYTES = 128
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "酷狗音乐登录响应格式无效" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
