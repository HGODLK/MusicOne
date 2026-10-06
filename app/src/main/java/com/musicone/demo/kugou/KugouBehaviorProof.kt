package com.musicone.demo

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal data class KugouBehaviorProof(val sid: String, val edt: String)

internal object KugouBehaviorProofFactory {
    private val secureRandom = SecureRandom()

    fun create(session: KugouSession, result: PlatformSecurityVerificationResult): KugouBehaviorProof {
        val key = ByteArray(16).also(secureRandom::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)
        val sentinel = 0xffff_ffffL - Random.nextLong(20)
        val width = result.viewportWidth.coerceAtLeast(1)
        val height = result.viewportHeight.coerceAtLeast(1)
        val events = buildList {
            add("5,0,0")
            add("5,$sentinel,0")
            add("5,0,0")
            add("5,$sentinel,0")
            add("6,1,0,$width,$height")
            add("6,$sentinel,0,$width,$height")
            result.touchPoints.forEachIndexed { index, point ->
                val subIndex = index % 2
                add("3,${point.elapsedMs.coerceAtLeast(0)},$subIndex,${point.x},${point.y}")
                add("3,$sentinel,$subIndex,${point.x},${point.y}")
            }
        }.joinToString(":")
        val webGl = KugouProtocol.md5("${session.mid}|${session.dfid}")
        val plaintext = "mid=${session.mid};userid=${session.userId};dfid=${session.dfid};" +
            "webgl=$webGl;webdriver=0;ts=${System.currentTimeMillis()};data=$events"
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(BEHAVIOR_IV.toByteArray()))
        val edt = Base64.encodeToString(aes.doFinal(plaintext.toByteArray()), Base64.NO_WRAP)

        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(RSA_MODULUS, 16), BigInteger.valueOf(65_537L)),
        )
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val sid = Base64.encodeToString(rsa.doFinal(key.toByteArray()), Base64.NO_WRAP)
        return KugouBehaviorProof(sid, edt)
    }

    private const val BEHAVIOR_IV = "kugousecurity123"
    private const val RSA_MODULUS =
        "a16dbe625a3c00b78f4904cfd31045945984387bc10fdb52facec30657ca12edd1cf3bd94da5f526d61b5f8f80554aa3" +
            "e80473f0833e08a072a8616f6c737f5bae17c4d23eabbcf7e9a8c22f75532765b91bd302262b5cea819b8ab7b83507e168" +
            "4ab49c2fa1c41590bc26c815f940d88b6b2d46d253bcf56c703f6be8e5426e0e5af63e20a9d3af23894cfb93d7234e563" +
            "6c9f3004b2b2d83810afda4fa963e6110b46a51e4833d57c29aa3a3da49d29839619b5f78b6f91cc82a1bd9531c6d270" +
            "7556ea3e50cf956f61e3fc4805ce7a2e0bebe1a225f2716dc1b8f85095544c5b86aecd2d63d1ffb57bd9db675408ab86c" +
            "56fe05bb645fa05f3eaf1ed61aad"
}
