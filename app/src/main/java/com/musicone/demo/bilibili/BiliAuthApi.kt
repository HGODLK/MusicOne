package com.musicone.demo

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher

/**
 * B站账号认证接口（扫码、短信登录与极验人机验证）。
 */
internal class BiliAuthApi(context: Context) {
    private val webSession = BiliWebSession(context)
    fun webHeaders(cookie: String, referer: String = "https://www.bilibili.com/") = webSession.headers(cookie, referer)
    val buvid: String by lazy {
        val prefs = context.applicationContext.getSharedPreferences("musicone_bili_device", Context.MODE_PRIVATE)
        var id = prefs.getString("buvid3", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString().replace("-", "").take(32).uppercase()
            prefs.edit().putString("buvid3", id).apply()
        }
        id
    }

    private var cachedMixinKey: String? = null
    private var lastMixinKeyTime: Long = 0L
    private val loginSessionId = UUID.randomUUID().toString().replace("-", "")
    private val appHeaders get() = mapOf("buvid" to buvid, "env" to "prod", "app-key" to "android_hd", "User-Agent" to APP_UA)

    val baseHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://www.bilibili.com",
            "Cookie" to "buvid3=$buvid",
        )

    fun generateQrCode(): BiliQrCode {
        val resp = PlatformHttp.get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", headers = baseHeaders)
        val json = JSONObject(resp.text)
        if (json.optInt("code") != 0) throw PlatformApiException(json.optString("message", "生成二维码失败"))
        val data = json.getJSONObject("data")
        return BiliQrCode(url = data.getString("url"), qrcodeKey = data.getString("qrcode_key"))
    }

    fun pollQrCode(qrcodeKey: String): BiliQrStatus {
        val resp = PlatformHttp.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey", headers = baseHeaders)
        val json = JSONObject(resp.text)
        val data = json.optJSONObject("data")
        val code = data?.optInt("code", 86038) ?: 86038
        val msg = data?.optString("message", "").orEmpty()

        val cookie = if (code == 0) {
            val cookieMap = resp.cookies().toMutableMap()
            data?.optString("url")?.let { redirectUrl ->
                val query = redirectUrl.substringAfter('?', "")
                query.split('&').forEach { param ->
                    val pair = param.split('=', limit = 2)
                    if (pair.size == 2 && pair[0].isNotBlank()) cookieMap[pair[0]] = pair[1]
                }
            }
            cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            null
        }
        return BiliQrStatus(code = code, message = msg, cookie = cookie)
    }

    fun getUserInfo(cookie: String): BiliAccountSession? {
        val headers = webHeaders(cookie)
        val resp = PlatformHttp.get("https://api.bilibili.com/x/web-interface/nav", headers = headers)
        val json = JSONObject(resp.text)
        if (json.optInt("code") != 0) return null
        val data = json.optJSONObject("data") ?: return null

        data.optJSONObject("wbi_img")?.let { wbi ->
            val imgUrl = wbi.optString("img_url", "")
            val subUrl = wbi.optString("sub_url", "")
            val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
            val subKey = subUrl.substringAfterLast('/').substringBefore('.')
            if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                cachedMixinKey = BiliSigner.getMixinKey(imgKey, subKey)
                lastMixinKeyTime = System.currentTimeMillis()
            }
        }

        if (!data.optBoolean("isLogin", false)) return null
        val mid = data.optLong("mid", 0L).toString()
        val uname = data.optString("uname", "")
        val face = data.optString("face", "")
        return BiliAccountSession(cookie = cookie, mid = mid, name = uname, avatar = face)
    }

    fun getOrFetchMixinKey(cookie: String): String {
        val cached = cachedMixinKey
        if (cached != null && System.currentTimeMillis() - lastMixinKeyTime < 43_200_000L) {
            return cached
        }
        getUserInfo(cookie)
        return cachedMixinKey ?: throw PlatformApiException("无法取得 WBI 签名，请稍后重试")
    }

    fun sendSmsCode(
        cid: String,
        phone: String,
        geetest: BiliGeetestResult? = null,
    ): BiliSmsSendResult {
        val ts = System.currentTimeMillis()
        val params = mutableMapOf(
            "build" to "2001100",
            "buvid" to buvid,
            "c_locale" to "zh_CN",
            "channel" to "master",
            "cid" to cid,
            "disable_rcmd" to "0",
            "local_id" to buvid,
            "login_session_id" to loginSessionId,
            "mobi_app" to "android_hd",
            "platform" to "android",
            "s_locale" to "zh_CN",
            "statistics" to "{\"appId\":5,\"platform\":3,\"version\":\"2.0.1\",\"abtest\":\"\"}",
            "tel" to phone,
            "ts" to (ts / 1000L).toString(),
        )
        if (geetest != null) {
            params["gee_challenge"] = geetest.challenge
            params["gee_seccode"] = geetest.seccode
            params["gee_validate"] = geetest.validate
            params["recaptcha_token"] = geetest.token
        }

        val signedParams = BiliSigner.signApp(params)
        val json = BiliAuthDiagnostics.request(if (geetest == null) "sms_send" else "sms_verified_send") {
            PlatformHttp.postForm(
                url = "https://passport.bilibili.com/x/passport-login/sms/send",
                values = signedParams,
                headers = appHeaders,
            )
        }
        val code = json.optInt("code", -1)
        val data = json.optJSONObject("data")

        if (code == 0 && data?.optString("recaptcha_url").isNullOrBlank() && !data?.optString("captcha_key").isNullOrBlank()) {
            val captchaKey = data?.optString("captcha_key").orEmpty()
            return BiliSmsSendResult.Success(captchaKey)
        }

        challengeFrom(data)?.let { return BiliSmsSendResult.NeedGeetest(it) }
        if (code == 0 || code == -105) {
            val pre = BiliAuthDiagnostics.request("captcha_pre") { PlatformHttp.postForm(
                "https://passport.bilibili.com/x/safecenter/captcha/pre", emptyMap(), headers = appHeaders,
            ) }
            if (pre.optInt("code", -1) == 0) challengeFrom(pre.optJSONObject("data"))?.let { return BiliSmsSendResult.NeedGeetest(it) }
            if (pre.optInt("code", -1) != 0) return BiliSmsSendResult.Failed(
                pre.optString("message").ifBlank { "获取安全验证失败，请重新获取验证码" }, pre.optInt("code", -1),
            )
            return BiliSmsSendResult.Failed("安全验证参数不完整，请重新获取验证码", code)
        }

        return BiliSmsSendResult.Failed(json.optString("message").ifBlank { "发送验证码失败，请重试" }, code)
    }

    fun loginBySms(
        cid: String,
        phone: String,
        code: String,
        captchaKey: String,
    ): BiliAccountSession {
        val keyResponse = JSONObject(PlatformHttp.get("https://passport.bilibili.com/x/passport-login/web/key", headers = baseHeaders).text)
        if (keyResponse.optInt("code", -1) != 0) throw PlatformApiException("无法获取登录公钥，请重试")
        val pem = keyResponse.getJSONObject("data").getString("key")
        val encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s+"), "")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT)))
        val encryptedDevice = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            Base64.encodeToString(doFinal(UUID.randomUUID().toString().replace("-", "").take(16).toByteArray()), Base64.NO_WRAP)
        }
        val params = mapOf(
            "bili_local_id" to buvid,
            "build" to "2001100",
            "buvid" to buvid,
            "c_locale" to "zh_CN",
            "captcha_key" to captchaKey,
            "channel" to "master",
            "cid" to cid,
            "code" to code,
            "device" to "phone",
            "device_id" to buvid,
            "dt" to BiliSigner.encode(encryptedDevice),
            "from_pv" to "main.my-information.my-login.0.click",
            "from_url" to BiliSigner.encode("bilibili://user_center/mine"),
            "disable_rcmd" to "0",
            "local_id" to buvid,
            "login_session_id" to loginSessionId,
            "mobi_app" to "android_hd",
            "platform" to "android",
            "s_locale" to "zh_CN",
            "statistics" to "{\"appId\":5,\"platform\":3,\"version\":\"2.0.1\",\"abtest\":\"\"}",
            "tel" to phone,
        )
        val signed = BiliSigner.signApp(params)
        val resp = PlatformHttp.postForm(
            url = "https://passport.bilibili.com/x/passport-login/login/sms",
            values = signed,
            headers = appHeaders,
        )
        val json = JSONObject(resp.text)
        if (json.optInt("code") != 0) throw PlatformApiException(json.optString("message", "验证码登录失败"))

        val data = json.getJSONObject("data")
        val cookieMap = resp.cookies().toMutableMap()
        data.optJSONObject("cookie_info")?.optJSONArray("cookies")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                cookieMap[item.getString("name")] = item.getString("value")
            }
        }
        val fullCookie = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return getUserInfo(fullCookie) ?: throw PlatformApiException("登录成功但验证用户信息失败，请重试")
    }

    private fun challengeFrom(data: JSONObject?): BiliGeetestChallenge? {
        data ?: return null
        val url = data.optString("recaptcha_url").takeIf(String::isNotBlank)?.let(Uri::parse)
        fun field(name: String) = url?.getQueryParameter(name).orEmpty().ifBlank { data.optString(name) }
        val gt = field("gee_gt")
        val challenge = field("gee_challenge")
        val token = field("recaptcha_token")
        return if (gt.isNotBlank() && challenge.isNotBlank() && token.isNotBlank()) BiliGeetestChallenge(gt, challenge, token) else null
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        const val APP_UA = "Mozilla/5.0 BiliDroid/2.0.1 os/android model/android_hd mobi_app/android_hd build/2001100 channel/master"
    }
}
