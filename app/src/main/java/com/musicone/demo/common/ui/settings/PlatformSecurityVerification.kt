package com.musicone.demo

internal enum class PlatformSecurityVerificationKind {
    WEB,
    TENCENT_CAPTCHA,
    SMS,
    MANUAL,
}

internal data class PlatformSecurityChallenge(
    val source: MusicSource,
    val kind: PlatformSecurityVerificationKind,
    val title: String = "安全验证",
    val message: String = "请完成平台安全验证",
    val url: String = "",
    val credentialSeed: String = "",
    val eventId: String = "",
    val verificationType: Int = 0,
    val appId: String = "",
)

internal data class PlatformSecurityVerificationUiState(
    val source: MusicSource,
    val kind: PlatformSecurityVerificationKind,
    val title: String,
    val message: String,
    val url: String,
    val appId: String,
    val credentialSeed: String,
    val instanceId: String = java.util.UUID.randomUUID().toString(),
)

internal data class PlatformTouchPoint(
    val elapsedMs: Long,
    val x: Int,
    val y: Int,
)

internal data class PlatformSecurityVerificationResult(
    val payload: String = "",
    val cookie: String = "",
    val touchPoints: List<PlatformTouchPoint> = emptyList(),
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
)

internal class PlatformSecurityVerificationRequired(
    val challenge: PlatformSecurityChallenge,
) : Exception(challenge.message)

internal fun PlatformSecurityChallenge.toUiState(): PlatformSecurityVerificationUiState =
    PlatformSecurityVerificationUiState(source, kind, title, message, url, appId, credentialSeed)

internal fun mergePlatformCredentials(vararg credentials: String): String = buildMap {
    credentials.forEach { putAll(it.cookieValues()) }
}.asCookieHeader()
