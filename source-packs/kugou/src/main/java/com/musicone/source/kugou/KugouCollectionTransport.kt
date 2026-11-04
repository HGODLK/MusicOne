package com.musicone.source.kugou

import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** 仅供云歌单管理使用，不改变原有登录、歌曲解析和播放请求。 */
internal interface KugouCollectionTransport {
    fun post(session: KugouSession, path: String, body: JSONObject,
        extra: Map<String, String> = emptyMap(), encrypted: Boolean = false,
        router: String? = null): JSONObject
}

internal class KugouCloudCollectionTransport : KugouCollectionTransport {
    override fun post(session: KugouSession, path: String, body: JSONObject,
        extra: Map<String, String>, encrypted: Boolean, router: String?): JSONObject {
        val now = (System.currentTimeMillis() / 1000).toString()
        val params = linkedMapOf("appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "clienttime" to now, "mid" to session.mid, "dfid" to session.dfid)
        val base = "https://gateway.kugou.com$path"
        if (!encrypted) {
            params.putAll(mapOf("uuid" to "-", "userid" to session.userId, "token" to session.token))
            params.putAll(extra)
            return JSONObject(PlatformHttp.postJson(KugouProtocol.signedAndroidUrl(base, params, body.toString()),
                body.toString(), session.cookie, kugouAndroidHeaders(params, router ?: kugouGatewayRouter(base))).text)
        }
        // 云歌单接口使用独立的六位会话串和 PKCS#1 RSA，不能套用登录的裸 RSA。
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
        val aes = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val digest = KugouProtocol.md5(aes)
        val hex = KugouLoginCrypto.encryptFixed(body.toString(), digest.take(16), digest.takeLast(16))
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val portrait = JSONObject().put("aes", aes).put("uid", session.userId).put("token", session.token)
        val keyBytes = android.util.Base64.decode(LITE_PUBLIC_KEY, android.util.Base64.DEFAULT)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes)))
        params["p"] = rsa.doFinal(portrait.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        params["key"] = KugouProtocol.loginKey(now.toLong())
        params.putAll(extra)
        val connection = URI(kugouQueryUrl(base, params)).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            (kugouAndroidHeaders(params, KUGOU_CLOUD_LIST_ROUTER) +
                mapOf("Content-Type" to "application/json;charset=utf-8", "Cookie" to session.cookie))
                .forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode !in 200..299) throw PlatformApiException("酷狗云歌单请求失败（${connection.responseCode}）")
            val raw = connection.inputStream.use { it.readBytes() }
            val decoded = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(), "AES"),
                    IvParameterSpec(digest.takeLast(16).toByteArray()))
                JSONObject(cipher.doFinal(raw).toString(Charsets.UTF_8))
            }.getOrNull()
            return decoded ?: JSONObject(raw.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LITE_PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
    }
}

/** 写操作必须由平台明确确认，缺状态或 status=0 都不是成功。 */
internal fun JSONObject.requireKugouCollectionSuccess() {
    val code = optInt("error_code", optInt("errcode", 0))
    if (optInt("status", -1) != 1 || code != 0) {
        throw PlatformApiException(optString("error").ifBlank { optString("msg") }
            .ifBlank { optString("message") }.ifBlank { "酷狗未确认歌单操作成功" }, code)
    }
}
