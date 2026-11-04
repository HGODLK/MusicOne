package com.musicone.source.netease

internal enum class NeteaseSecurityVerificationKind {
    WEB,
    TENCENT_CAPTCHA,
    SMS,
    MANUAL,
}

internal data class NeteaseSecurityChallenge(
    val source: NeteaseOrigin,
    val kind: NeteaseSecurityVerificationKind,
    val title: String = "安全验证",
    val message: String = "请完成平台安全验证",
    val url: String = "",
    val credentialSeed: String = "",
    val eventId: String = "",
    val verificationType: Int = 0,
    val appId: String = "",
)

internal data class NeteaseSecurityVerificationUiState(
    val source: NeteaseOrigin,
    val kind: NeteaseSecurityVerificationKind,
    val title: String,
    val message: String,
    val url: String,
    val appId: String,
    val credentialSeed: String,
    val instanceId: String = java.util.UUID.randomUUID().toString(),
)

internal data class NeteaseTouchPoint(
    val elapsedMs: Long,
    val x: Int,
    val y: Int,
)

internal data class NeteaseSecurityVerificationResult(
    val payload: String = "",
    val cookie: String = "",
    val touchPoints: List<NeteaseTouchPoint> = emptyList(),
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
)

internal class NeteaseSecurityVerificationRequired(
    val challenge: NeteaseSecurityChallenge,
) : Exception(challenge.message)

internal fun NeteaseSecurityChallenge.toUiState(): NeteaseSecurityVerificationUiState =
    NeteaseSecurityVerificationUiState(source, kind, title, message, url, appId, credentialSeed)

internal fun mergeNeteaseCredentials(vararg credentials: String): String = buildMap {
    credentials.forEach { putAll(it.cookieValues()) }
}.asCookieHeader()
