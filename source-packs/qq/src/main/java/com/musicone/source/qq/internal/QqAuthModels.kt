package com.musicone.source.qq.internal

internal data class PlatformQrChallenge(
    val source: MusicSource,
    val key: String,
    val url: String = "",
    val imageBase64: String = "",
    val credentialSeed: String = "",
    val loginMethod: PlatformLoginMethod = PlatformLoginMethod.QR_CODE,
    val deviceId: String = "",
    val scanMessage: String = "",
    val verificationCredential: String = "",
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
    fun createQrCode(method: PlatformLoginMethod, deviceId: String): PlatformQrChallenge
    fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult
    fun prepareCaptchaCredential(session: PlatformSession, credentialSeed: String): String = credentialSeed
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

