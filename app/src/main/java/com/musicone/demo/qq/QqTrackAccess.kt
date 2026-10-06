package com.musicone.demo

import org.json.JSONObject

/** 保存歌曲原始限制，账号变化时重新计算角标，不能只保存已经消除的显示结果。 */
data class QqTrackAccessInfo(
    val payPlay: Int? = null,
    val payMonth: Int? = null,
    val trackPrice: Int? = null,
    val albumPrice: Int? = null,
    val payStatus: Int? = null,
    val icons: Long = 0L,
    val icon2: Long = 0L,
    val trial: Boolean = false,
    val permissionAccountId: String = "",
    val checkedAtMs: Long = 0L,
) {
    internal val superVip: Boolean get() = icon2 and 2048L != 0L
    internal val requiresVip: Boolean get() = (payMonth ?: 0) > 0 || icons and 262144L != 0L || superVip
    internal val kind: MusicAccessBadge? get() = when {
        requiresVip -> MusicAccessBadge.VIP
        payPlay == 0 -> null
        (trackPrice ?: 0) > 0 || (albumPrice ?: 0) > 0 -> MusicAccessBadge.PAID
        payPlay == 1 -> MusicAccessBadge.VIP
        else -> null
    }
    internal val complete: Boolean get() = payPlay == 0 || requiresVip ||
        ((trackPrice ?: 0) > 0 || (albumPrice ?: 0) > 0) && payMonth != null ||
        payPlay == 1 && payMonth != null && (trackPrice != null || albumPrice != null)

    internal fun access(accountId: String, vip: Boolean, superVipAccess: Boolean = vip,
                        nowMs: Long = System.currentTimeMillis()): MusicTrackAccess {
        val purchased = accountId.isNotBlank() && permissionAccountId == accountId &&
            (payStatus ?: 0) > 0 && nowMs - checkedAtMs in 0 until QQ_ENTITLEMENT_TTL_MS
        val badge = kind.takeUnless {
            purchased || (it == MusicAccessBadge.VIP && if (superVip) superVipAccess else vip)
        }
        return MusicTrackAccess(badge, trial && badge != null, badge == null || trial)
    }
}

internal const val QQ_ENTITLEMENT_TTL_MS = 5 * 60 * 1_000L

internal fun JSONObject.qqTrackAccessInfo(trial: Boolean): QqTrackAccessInfo {
    val pay = optJSONObject("pay") ?: optJSONObject("Pay") ?: this
    val action = optJSONObject("action") ?: optJSONObject("Action") ?: this
    return QqTrackAccessInfo(
        payPlay = pay.qqAccessInt("pay_play", "payplay"),
        payMonth = pay.qqAccessInt("pay_month", "paymonth", "pay_track_month", "paytrackmonth", "paytrackmouth"),
        trackPrice = pay.qqAccessInt("price_track", "paytrackprice"),
        albumPrice = pay.qqAccessInt("price_album", "payalbumprice"),
        payStatus = pay.qqAccessInt("pay_status", "paystatus"),
        icons = action.qqAccessLong("icons") ?: 0L,
        icon2 = action.qqAccessLong("icon2") ?: 0L,
        trial = trial,
    )
}

internal fun MusicTrack.withQqAccess(accountId: String, vip: Boolean, superVip: Boolean = vip,
                                     nowMs: Long = System.currentTimeMillis()): MusicTrack {
    if (source != MusicSource.QQ) return this
    val info = qqAccess ?: return this
    if (!info.complete && info.kind == null) return this
    val access = info.access(accountId, vip, superVip, nowMs)
    return copy(accessBadge = access.badge, trialAvailable = access.trialAvailable,
        playable = access.playable, unavailableReason = if (access.playable) null else "当前账号无权播放这首歌曲")
}

internal fun MusicTrack.withQqVipEntitlement(hasVipAccess: Boolean): MusicTrack =
    if (qqAccess != null) withQqAccess("", hasVipAccess)
    else if (source == MusicSource.QQ && hasVipAccess && accessBadge == MusicAccessBadge.VIP) {
        copy(accessBadge = null, trialAvailable = false, playable = true, unavailableReason = null)
    } else this

/** 播放地址只更新可播放状态，不能证明会员身份或购买权益。 */
internal fun MusicTrack.withQqPlaybackPermission(hasFullAccess: Boolean): MusicTrack =
    copy(playable = hasFullAccess || trialAvailable, unavailableReason = if (hasFullAccess) null else unavailableReason)

internal fun qqTrackAccess(requiresPayment: Boolean, monthly: Boolean, price: Int,
                           trialAvailable: Boolean, hasVipAccess: Boolean = false): MusicTrackAccess =
    QqTrackAccessInfo(payPlay = if (requiresPayment) 1 else 0,
        payMonth = if (monthly && requiresPayment) 1 else 0, trackPrice = price,
        trial = trialAvailable).access("", hasVipAccess)

internal fun QqTrackAccessInfo.toJson(): JSONObject = JSONObject()
    .put("payPlay", payPlay).put("payMonth", payMonth).put("trackPrice", trackPrice)
    .put("albumPrice", albumPrice).put("payStatus", payStatus).put("icons", icons).put("icon2", icon2)
    .put("trial", trial).put("accountId", permissionAccountId).put("checkedAtMs", checkedAtMs)

internal fun JSONObject.toQqTrackAccessInfo(): QqTrackAccessInfo = QqTrackAccessInfo(
    qqAccessInt("payPlay"), qqAccessInt("payMonth"), qqAccessInt("trackPrice"), qqAccessInt("albumPrice"),
    qqAccessInt("payStatus"), optLong("icons"), optLong("icon2"), optBoolean("trial"),
    optString("accountId"), optLong("checkedAtMs"),
)

internal fun JSONObject.qqAccessLong(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else opt(name)?.toString()?.toLongOrNull()
}

internal fun JSONObject.qqAccessInt(vararg names: String): Int? = qqAccessLong(*names)?.toInt()
