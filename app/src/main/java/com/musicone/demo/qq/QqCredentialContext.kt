package com.musicone.demo

import org.json.JSONObject

/** 播放、认证和会话统一使用音乐账号 ID，旧网页或微信身份只作为后备。 */
internal fun qqCredentialAccountId(credential: String): String {
    val values = credential.cookieValues()
    return QQ_ACCOUNT_ID_FIELDS.firstNotNullOfOrNull { name ->
        values[name]?.normalizedQqAccountId()
    }.orEmpty()
}

/** 只记录字段来源、存在性和一致性，不记录任何账号、设备或凭据原值。 */
internal fun qqTicketContextDiagnostic(body: JSONObject, credential: String): String {
    val values = credential.cookieValues()
    val identityField = QQ_ACCOUNT_ID_FIELDS.firstOrNull { values[it]?.normalizedQqAccountId() != null }
    val account = qqCredentialAccountId(credential)
    val comm = body.optJSONObject("comm")
    val params = body.optJSONObject("req_1")?.optJSONObject("param")
    val authId = comm?.optString("qq").orEmpty().ifBlank { comm?.optString("uin").orEmpty() }
    val parameterId = params?.optString("uin").orEmpty()
    return "账号字段=${identityField ?: "无"}，认证与换票账号一致=${authId == parameterId && parameterId == account}，" +
        "有音乐凭据=${qqCredentialMusicKey(credential).isNotBlank()}，ct=${comm?.optInt("ct")}，" +
        "platform=${params?.optString("platform")}，文件数=${params?.optJSONArray("filename")?.length()}"
}

private fun String.normalizedQqAccountId(): String? = trim().removePrefix("o").trimStart('0')
    .takeIf { it.isNotBlank() && it.all(Char::isDigit) }

private val QQ_ACCOUNT_ID_FIELDS = listOf(
    "str_musicid", "musicid", "qqmusic_uin", "userid", "user_id",
    "uin", "wxuin", "ptui_loginuin", "luin", "pt2gguin", "superuin", "p_uin",
)
