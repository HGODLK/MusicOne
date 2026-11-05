package com.musicone

import org.json.JSONObject

internal data class AiPlaybackValidation(
    val playableTracks: List<MusicTrack>,
    val skipped: List<JSONObject>,
    val unknown: List<JSONObject>,
)

/** false 仅表示未声明检测器；已声明但未确认会员的 null 不伪装成确定无会员。 */
internal fun aiSearchTier(kind: com.musicone.source.sdk.SourceKind, current: Boolean,
    detectorDeclared: Boolean, membership: Boolean?, authenticationDeclared: Boolean = true): Int? = when {
    kind == com.musicone.source.sdk.SourceKind.MAIN && current && membership == true -> 1
    // 没有声明登录能力的开放搜索源不应被会员检测结果挡住；播放仍单独校验权益。
    !authenticationDeclared -> 2
    kind == com.musicone.source.sdk.SourceKind.MAIN && (membership == true || current) -> 2
    kind == com.musicone.source.sdk.SourceKind.SUPPLEMENTAL && (!detectorDeclared || membership == true) -> 2
    else -> null
}

internal fun aiMembershipStatus(detectorDeclared: Boolean, membership: Boolean?): String = when {
    !detectorDeclared -> "not_declared"
    membership == true -> "member"
    else -> "unconfirmed"
}

internal fun aiPlaybackIssue(track: MusicTrack, status: String, reason: String): JSONObject =
    JSONObject().put("id", track.id).put("title", track.title).put("sourceId", track.source.id)
        .put("status", status).put("reason", reason)
