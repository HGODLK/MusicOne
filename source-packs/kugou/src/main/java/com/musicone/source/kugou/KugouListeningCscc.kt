package com.musicone.source.kugou

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Collections
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class KugouListeningDevice(val model: String, val system: String, val width: Int, val height: Int,
    val localIp: String?) {
    companion object {
        fun read(context: Context): KugouListeningDevice {
            val metrics = context.resources.displayMetrics
            val address = runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence().filter { it.isUp && !it.isLoopback }
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            }.getOrNull()
            return KugouListeningDevice(Build.MODEL, Build.VERSION.RELEASE, metrics.widthPixels, metrics.heightPixels, address)
        }
    }
}

internal data class KugouCsccRequest(val url: String, val method: String, val body: ByteArray?, val headers: Map<String, String>)
internal data class KugouCsccSession(val cookie: String, val clienttime: String, val serverstr: String, val field2: String)

/** CSCC 只发送真实开始、完成或中断；不复制参考报文中的页面、触摸、会员或设备数据。 */
internal class KugouListeningCscc(
    private val device: () -> KugouListeningDevice,
    private val now: () -> Long = System::currentTimeMillis,
    private val send: suspend (KugouCsccRequest, () -> Unit) -> JSONObject = { request, check ->
        runInterruptible(Dispatchers.IO) { check(); kugouSendCscc(request) }.also { check() }
    },
) {
    private var owner: String? = null
    private var active: KugouCsccSession? = null

    suspend fun report(cookie: String, songId: Long, started: Boolean, playedMs: Long, completed: Boolean,
        occurredAtMs: Long, check: () -> Unit) {
        require(songId > 0 && playedMs >= 0)
        check()
        val account = cookie.requiredKugouSession()
        val uuid = cookie.cookieValues()["KUGOU_API_GUID"].orEmpty().replace("-", "")
        require(uuid.length == 32 && uuid.all { it.isLetterOrDigit() && it.code < 128 }) { "酷狗播放上报缺少设备身份" }
        val currentDevice = device().also { value ->
            require(value.width > 0 && value.height > 0 && value.model.safeCsccField() && value.system.safeCsccField())
        }
        // 只缓存一个账号的握手，换号或凭证更新即丢弃旧会话。
        if (owner != cookie) { owner = cookie; active = null }
        try {
            val session = active ?: createSession(account, uuid, currentDevice, check).also { active = it }
            check()
            val request = kugouCsccPlaybackRequest(session, account, uuid, currentDevice, songId, started,
                playedMs, completed, occurredAtMs, now())
            requireKugouCsccSuccess(send(request, check))
        } catch (failure: Exception) {
            active = null
            // 请求超时可能已经入账；不重试同一播放事件。
            throw failure
        }
    }

    private suspend fun createSession(account: KugouSession, uuid: String, device: KugouListeningDevice,
        check: () -> Unit): KugouCsccSession {
        var time = (now() / 1_000).toString()
        val body = JSONObject().put("machine", device.model).put("mid", account.mid).put("uuid", uuid)
            .put("wh", org.json.JSONArray(listOf(device.width, device.height))).toString()
        val deviceResult = send(KugouCsccRequest(kugouQueryUrl("https://d.kugou.com/v3/qrydid", mapOf(
            "appid" to "and02", "_t" to time,
            "sign" to KugouProtocol.md5("_t${time}appidand02pbKC7zn{4U*ydo2M1Rir$body"))), "POST",
            body.toByteArray(), mapOf("Content-Type" to "application/json; charset=utf-8")), check)
        requireKugouCsccSuccess(deviceResult)
        val deviceId = deviceResult.optJSONObject("data")?.optString("deviceid").orEmpty()
        require(deviceId.isNotBlank()) { "酷狗播放上报未返回设备编号" }
        val field2 = ByteArray(16).also(SecureRandom()::nextBytes).hex()
        val source = "$uuid\t$field2\t${now()}\t$deviceId"
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(LITE_RSA_MODULUS, 16), BigInteger.valueOf(65537)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey) }
        val encrypted = Base64.encodeToString(rsa.doFinal(source.toByteArray()), Base64.NO_WRAP)
        time = (now() / 1_000).toString()
        val result = send(KugouCsccRequest(kugouQueryUrl("https://d.kugou.com/v2/gen", mapOf(
            "s" to encrypted, "appid" to KugouProtocol.appId, "_t" to time,
            "sign" to KugouProtocol.md5("_t${time}appid${KugouProtocol.appId}s${encrypted}$LITE_APP_KEY"))),
            "GET", null, csccHeaders(device, "Gen")), check)
        requireKugouCsccSuccess(result)
        val decoded = csccAes(Cipher.DECRYPT_MODE, Base64.decode(result.getString("data"), Base64.DEFAULT), field2, uuid.take(16))
        val session = JSONObject(decoded.toString(Charsets.UTF_8))
        return KugouCsccSession(session.getString("cookie"), session.get("clienttime").toString(),
            session.getString("serverstr"), field2).also {
            require(it.cookie.isNotBlank() && it.serverstr.isNotBlank() && it.clienttime.isNotBlank())
        }
    }
}

internal fun kugouCsccPlaybackRequest(session: KugouCsccSession, account: KugouSession, uuid: String,
    device: KugouListeningDevice, songId: Long, started: Boolean, playedMs: Long, completed: Boolean,
    occurredAtMs: Long, nowMs: Long): KugouCsccRequest {
    val fields = linkedMapOf("type_id" to if (started) "20431" else "4", "action" to "play", "mixsongid" to songId.toString())
    if (!started) fields.putAll(mapOf("duration" to playedMs.toString(), "state" to if (completed) "完整播放" else "中断播放"))
    fields.putAll(mapOf("mid" to account.mid, "uuid" to uuid, "sys" to device.system, "mod" to device.model,
        "channelid" to "18", "ver" to KugouProtocol.clientVersion, "time" to occurredAtMs.toString(),
        "userid" to account.userId, "ss3" to KugouProtocol.md5(UUID.randomUUID().toString())))
    device.localIp?.takeIf(String::safeCsccField)?.let { fields["ip"] = it }
    require(fields.values.all(String::safeCsccField)) { "酷狗播放上报参数格式无效" }
    val event = fields.entries.joinToString("&") { "${it.key}=${it.value}" }.toByteArray()
    val header = listOf("4", "1", uuid, "0", KugouProtocol.appId, KugouProtocol.clientVersion, "18", device.system,
        device.model, uuid, account.mid, "0", "000000000000000000000000000000000000").joinToString("\t")
    val line = listOf(event.size, "10048", "0", occurredAtMs / 1_000, "1", account.userId, "0", "0").joinToString("\t")
    val plain = "$header\r\n$line\r\n".toByteArray() + deflate(event)
    val key = KugouProtocol.md5(uuid + session.clienttime + session.field2 + session.serverstr)
    val encrypted = csccAes(Cipher.ENCRYPT_MODE, deflate(plain), key, uuid.take(16))
    val query = linkedMapOf("cookie" to session.cookie, "length" to plain.size.toString(), "appid" to KugouProtocol.appId,
        "_t" to (nowMs / 1_000).toString())
    val sorted = query.entries.sortedBy { it.key }.joinToString("") { it.key + it.value }
    query["sign"] = MessageDigest.getInstance("MD5").digest((sorted + LITE_APP_KEY).toByteArray() + encrypted).hex()
    return KugouCsccRequest(kugouQueryUrl("https://d.kugou.com/v2/post", query), "POST", encrypted,
        csccHeaders(device, "Post") + ("Content-Type" to "application/octet-stream"))
}

private fun requireKugouCsccSuccess(result: JSONObject) {
    require((!result.has("status") || result.optInt("status", 0) == 1) &&
        (result.has("status") || result.has("errcode") || result.has("error_code")) &&
        result.optInt("error_code", result.optInt("errcode", 0)) == 0) { "酷狗未确认播放事件成功" }
}

private fun kugouSendCscc(request: KugouCsccRequest): JSONObject {
    val connection = URI.create(request.url).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = request.method
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = false
        for ((name, value) in request.headers) connection.setRequestProperty(name, value)
        request.body?.let { body -> connection.doOutput = true; connection.outputStream.use { it.write(body) } }
        require(connection.responseCode in 200..299) { "酷狗播放事件请求失败（HTTP ${connection.responseCode}）" }
        return JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
    } finally { connection.disconnect() }
}

private fun csccHeaders(device: KugouListeningDevice, kind: String) = mapOf("KG-Rec" to "1",
    "User-Agent" to "Android${device.system}-1070-${KugouProtocol.clientVersion}-18-0-Cscc$kind")
private fun String.safeCsccField() = isNotBlank() && none { it == '&' || it.code < 32 || it.code == 127 }
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    DeflaterOutputStream(output).use { it.write(bytes) }
}.toByteArray()
private fun csccAes(mode: Int, bytes: ByteArray, key: String, iv: String): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
    }.doFinal(bytes)
private const val LITE_APP_KEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
private const val LITE_RSA_MODULUS = "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af767" +
    "9b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525" +
    "d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724" +
    "336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b"
