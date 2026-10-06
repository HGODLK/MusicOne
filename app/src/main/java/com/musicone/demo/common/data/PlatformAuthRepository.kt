package com.musicone.demo

internal data class PlatformQrChallenge(
    val source: MusicSource,
    val key: String,
    val url: String = "",
    val imageBase64: String = "",
    val credentialSeed: String = "",
)

internal data class PlatformQrResult(
    val status: PlatformQrLoginStatus,
    val message: String,
    val credential: String,
    val account: MusicAccount? = null,
)

internal data class PlatformCaptchaChallenge(
    val message: String,
    val credentialSeed: String = "",
)

internal data class PlatformSmsLoginResult(
    val credential: String,
    val account: MusicAccount,
)

internal interface PlatformAuthAdapter {
    val source: MusicSource
    fun createQrCode(): PlatformQrChallenge
    fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult
    fun sendCaptcha(
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge
    fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult
    fun completeSecurityVerification(
        challenge: PlatformSecurityChallenge,
        result: PlatformSecurityVerificationResult,
    ): String = mergePlatformCredentials(challenge.credentialSeed, result.cookie)
    fun account(credential: String): MusicAccount
}

internal class PlatformAuthRepository {
    private val adapters = listOf<PlatformAuthAdapter>(
        NeteaseAuthAdapter(NeteaseApiClient()),
        QqAuthAdapter(QqApiClient()),
        KugouAuthAdapter(KugouApiClient()),
    ).associateBy(PlatformAuthAdapter::source)

    fun supports(source: MusicSource): Boolean = adapters.containsKey(source)

    fun createQrCode(source: MusicSource): PlatformQrChallenge = adapter(source).createQrCode()

    fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult = adapter(challenge.source).checkQrCode(challenge)

    fun sendCaptcha(
        source: MusicSource,
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge = adapter(source).sendCaptcha(phone, countryCode, session, credentialSeed)

    fun loginByCaptcha(
        source: MusicSource,
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult = adapter(source).loginByCaptcha(
        phone,
        countryCode,
        captcha,
        session,
        credentialSeed,
    )

    fun account(source: MusicSource, credential: String): MusicAccount = adapter(source).account(credential)

    fun completeSecurityVerification(
        challenge: PlatformSecurityChallenge,
        result: PlatformSecurityVerificationResult,
    ): String = adapter(challenge.source).completeSecurityVerification(challenge, result)

    private fun adapter(source: MusicSource): PlatformAuthAdapter = adapters[source]
        ?: throw PlatformApiException("${source.label}暂不支持登录")
}

private class KugouAuthAdapter(
    private val api: KugouApiClient,
    private val sms: KugouSmsLoginClient = KugouSmsLoginClient(),
) : PlatformAuthAdapter {
    override val source = MusicSource.KUGOU

    override fun createQrCode(): PlatformQrChallenge = api.createQrCode().let {
        PlatformQrChallenge(source, it.key, url = it.url, credentialSeed = it.deviceCookie)
    }

    override fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult =
        api.checkQrCode(challenge.key, challenge.credentialSeed).let {
            PlatformQrResult(it.status, it.message, it.cookie, it.account)
        }

    override fun sendCaptcha(
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge = sms.sendCaptcha(
        phone,
        countryCode,
        credentialSeed.ifBlank { session.credential },
    ).let {
        PlatformCaptchaChallenge(it.message, it.deviceCookie)
    }

    override fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult = sms.loginByCaptcha(phone, countryCode, captcha, credentialSeed).let {
        PlatformSmsLoginResult(it.cookie, it.account)
    }

    override fun completeSecurityVerification(
        challenge: PlatformSecurityChallenge,
        result: PlatformSecurityVerificationResult,
    ): String = sms.completeSecurityVerification(challenge, result)

    override fun account(credential: String): MusicAccount = api.account(credential)
}

private class NeteaseAuthAdapter(private val api: NeteaseApiClient) : PlatformAuthAdapter {
    override val source = MusicSource.NETEASE

    override fun createQrCode(): PlatformQrChallenge = api.createQrCode().let {
        PlatformQrChallenge(source, it.key, url = it.url, credentialSeed = it.cookie)
    }

    override fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult =
        api.checkQrCode(challenge.key, challenge.credentialSeed).let {
            PlatformQrResult(it.status, it.message, it.cookie, it.account)
        }

    override fun sendCaptcha(
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge = api.sendCaptcha(
        phone,
        countryCode,
        session.deviceId,
        mergePlatformCredentials(session.credential, credentialSeed),
    ).let { PlatformCaptchaChallenge(it.message, it.cookie) }

    override fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult = api.loginByCaptcha(
        phone,
        countryCode,
        captcha,
        session.deviceId,
        credentialSeed,
    ).let { PlatformSmsLoginResult(it.cookie, it.account) }

    override fun account(credential: String): MusicAccount = api.account(credential)
}

private class QqAuthAdapter(
    private val api: QqApiClient,
    private val sms: QqSmsLoginClient = QqSmsLoginClient(),
) : PlatformAuthAdapter {
    override val source = MusicSource.QQ

    override fun createQrCode(): PlatformQrChallenge =
        throw PlatformApiException("QQ 音乐仅支持短信验证码登录")

    override fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult =
        throw PlatformApiException("QQ 音乐仅支持短信验证码登录")

    override fun sendCaptcha(
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge = sms.sendCaptcha(
        phone,
        countryCode,
        session.deviceId,
        mergePlatformCredentials(session.credential, credentialSeed),
    ).let { PlatformCaptchaChallenge(it.message, it.cookie) }

    override fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult = sms.loginByCaptcha(phone, captcha, session.deviceId, credentialSeed).let { result ->
        PlatformSmsLoginResult(result.cookie, runCatching { api.account(result.cookie) }.getOrDefault(result.account))
    }

    override fun account(credential: String): MusicAccount = api.account(credential)
}
