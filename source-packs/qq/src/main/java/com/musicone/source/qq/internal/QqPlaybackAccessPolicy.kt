package com.musicone.source.qq.internal

/** 只有当前账号在有效期内明确查到的限制信息，才能直接拦截播放。 */
internal fun confirmedQqPlaybackRestriction(info: QqTrackAccessInfo?, accountId: String,
    membership: QqMembership?, nowMs: Long = System.currentTimeMillis()): String? {
    if (info == null || !info.complete || info.trial || accountId.isBlank() ||
        info.permissionAccountId != accountId || nowMs - info.checkedAtMs !in 0 until QQ_ENTITLEMENT_TTL_MS ||
        info.payStatus != 0) return null
    val member = membership?.takeIf { nowMs < it.validUntilMs }?.effectiveAt(nowMs)
    val denied = when (info.kind) {
        MusicAccessBadge.PAID -> true
        MusicAccessBadge.VIP -> member != null && !(if (info.superVip) member.superVip else member.vip)
        null -> false
    }
    return "当前账号无权播放这首歌曲".takeIf { denied }
}

/** 判断歌曲是否缺时长或 songMid，缺的话播放前先补一次详情。 */
internal fun MusicTrack.requiresQqPlaybackMetadata(): Boolean =
    source == MusicSource.QQ && (durationMs <= 0L || qqPlaybackMid().isBlank())
