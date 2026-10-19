package com.musicone.demo

internal class QqAuthAdapter(
    private val api: QqApiClient,
    private val sms: QqSmsLoginClient = QqSmsLoginClient(),
    private val qr: QqQrLoginClient = QqQrLoginClient(),
) : PlatformAuthAdapter {
    override val source = MusicSource.QQ

    override fun createQrCode(method: PlatformLoginMethod, deviceId: String): PlatformQrChallenge =
        qr.createQrCode(method, deviceId)

    override fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult = qr.checkQrCode(challenge).let { result ->
        val account = result.account ?: return@let result
        result.copy(account = runCatchingCancellable { api.account(result.credential) }.getOrDefault(account))
    }

    override fun sendCaptcha(
        phone: String,
        countryCode: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformCaptchaChallenge = sms.sendCaptcha(
        phone, countryCode, session.deviceId, mergePlatformCredentials(session.credential, credentialSeed),
    ).let { PlatformCaptchaChallenge(it.message, it.cookie) }

    override fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        session: PlatformSession,
        credentialSeed: String,
    ): PlatformSmsLoginResult = sms.loginByCaptcha(phone, captcha, session.deviceId, credentialSeed).let { result ->
        PlatformSmsLoginResult(result.cookie, runCatchingCancellable { api.account(result.cookie) }.getOrDefault(result.account))
    }

    override fun account(credential: String): MusicAccount = api.account(credential)
}
