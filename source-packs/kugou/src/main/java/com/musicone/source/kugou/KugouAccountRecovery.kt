package com.musicone.source.kugou

/** 34351 是账号注销状态，恢复由官方页面完成，不能当成验证码错误重复提交。 */
internal fun kugouAccountRecoveryChallenge(cookie: String): PlatformSecurityChallenge = PlatformSecurityChallenge(
    source = MusicSource.KUGOU,
    kind = PlatformSecurityVerificationKind.WEB,
    title = "恢复酷狗音乐账号",
    message = "该账号已在酷狗注销。请在官方页面完成恢复，返回后重新获取短信验证码登录。",
    url = "https://h5.kugou.com/recover/v-e063c2ff/index.html?type=mobile",
    credentialSeed = cookie,
)

internal fun kugouAccountRecoveryChallengeForError(
    errorCode: Int,
    cookie: String,
): PlatformSecurityChallenge? =
    if (errorCode == 34_351) kugouAccountRecoveryChallenge(cookie) else null

internal fun PlatformSecurityChallenge.isKugouAccountRecovery(): Boolean =
    source == MusicSource.KUGOU && url == "https://h5.kugou.com/recover/v-e063c2ff/index.html?type=mobile"


