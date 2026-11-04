package com.musicone.source.qq.internal

/** 只有当前账号、有效时间内的明确权益结果才允许判定不可播放。 */
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

/** 先恢复短卡片缺少的时长与歌曲身份，真实音源验证仍由播放解析执行。 */
internal fun MusicTrack.requiresQqPlaybackMetadata(): Boolean =
    source == MusicSource.QQ && (durationMs <= 0L || qqPlaybackMid().isBlank())
