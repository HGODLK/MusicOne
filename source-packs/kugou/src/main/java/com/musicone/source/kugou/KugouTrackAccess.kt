package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 保留歌曲收费类型，账号会员变化时重新计算角标；播放地址不能证明会员或购买权益。 */
internal data class KugouTrackAccessInfo(
    val purchased: Boolean,
    val paidOnly: Boolean,
    val needsVip: Boolean,
    val trialAvailable: Boolean,
    val purchaseAccountId: String = "",
    val classificationVersion: Int = 5,
) {
    fun bindPurchase(accountId: String?): KugouTrackAccessInfo =
        if (purchased && purchaseAccountId.isBlank() && !accountId.isNullOrBlank()) copy(purchaseAccountId = accountId) else this

    fun access(accountId: String?, hasVipAccess: Boolean): MusicTrackAccess {
        val hasPurchase = purchased && !accountId.isNullOrBlank() && purchaseAccountId == accountId
        val badge = when {
            hasPurchase -> null
            needsVip -> if (hasVipAccess) null else MusicAccessBadge.VIP
            paidOnly -> MusicAccessBadge.PAID
            else -> null
        }
        return MusicTrackAccess(badge, badge != null && trialAvailable, true)
    }

    fun sourceAccess(previous: TrackAccessInfo, accountId: String?, hasVipAccess: Boolean): TrackAccessInfo {
        val access = access(accountId, hasVipAccess)
        return TrackAccessInfo(badge = when (access.badge) {
            MusicAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP
            MusicAccessBadge.PAID -> TrackAccessBadge.PURCHASE
            null -> null
        }, trialAvailable = access.trialAvailable, playable = previous.playable, reason = previous.reason)
    }
}

/** feeType 只说明收费属性，不能单独证明必须另行购买；优先使用明确的歌曲收费类型。 */
internal fun JSONObject.kugouTrackAccessInfo(): KugouTrackAccessInfo? {
    val payType = kugouAccessInt("PayType", "paytype", "pay_type")
    val privilege = kugouAccessInt("Privilege", "privilege")
    val feeType = kugouAccessInt("feetype", "fee_type")
    val purchased = kugouAccessInt("is_buy", "is_bought", "purchased", "has_buy")
    if (payType == null && privilege == null && feeType == null) return null
    if (payType == null && privilege == null && feeType != 0) return null
    val trans = optJSONObject("trans_param") ?: optJSONObject("TransParam")
    // PayType 是可组合的购买途径位：1 为会员，2 为单曲购买；3 同时允许两种途径。
    // 补查结果可能省略 Privilege，不能把会员途径 1 误当作必须购买。
    val needsVip = (payType != null && payType and 1 != 0) || privilege == 8 || privilege == 10
    return KugouTrackAccessInfo(
        purchased = purchased == 1,
        paidOnly = (payType != null && payType and 2 != 0) && !needsVip,
        needsVip = needsVip,
        trialAvailable = trans?.optJSONObject("hash_offset") != null ||
            trans?.optString("clip_hash").orEmpty().isNotBlank() ||
            kugouAccessInt("FailProcess", "failprocess", "fail_process") == 4 || privilege == 8,
    )
}

internal fun KugouTrackAccessInfo.toJson(): JSONObject = JSONObject()
    .put("purchased", purchased).put("paidOnly", paidOnly).put("needsVip", needsVip)
    .put("trialAvailable", trialAvailable).put("purchaseAccountId", purchaseAccountId)
    .put("classificationVersion", classificationVersion)

internal fun JSONObject.toKugouTrackAccessInfo(): KugouTrackAccessInfo = KugouTrackAccessInfo(
    getBoolean("purchased"), getBoolean("paidOnly"), getBoolean("needsVip"),
    getBoolean("trialAvailable"), optString("purchaseAccountId"), optInt("classificationVersion", 1),
)

internal fun SourceTrackDetails.kugouRawAccess(): KugouTrackAccessInfo? = runCatching {
    val encoded = track.opaqueData["kugouAccess"] ?: JSONObject(payload.encoded).optString("kugouAccess")
    encoded.takeIf(String::isNotBlank)?.let { JSONObject(it).toKugouTrackAccessInfo() }
}.getOrNull()

internal fun SourceTrackDetails.withKugouRawAccess(info: KugouTrackAccessInfo): SourceTrackDetails {
    val encoded = info.toJson().toString()
    val data = runCatching { JSONObject(payload.encoded) }.getOrDefault(JSONObject()).put("kugouAccess", encoded)
    return copy(track = track.copy(opaqueData = track.opaqueData + ("kugouAccess" to encoded)),
        payload = payload.copy(encoded = data.toString()))
}

/** 新歌曲直接使用原始收费信息；旧快照缺失信息时仅补查标准档权益，不改播放取址。 */
internal class KugouTrackAccessResolver(
    private val details: (MusicTrack, String) -> JSONObject = ::requestKugouTrackAccess,
) {
    fun access(track: SourceTrackDetails, cookie: String, hasVipAccess: Boolean): TrackAccessInfo {
        val accountId = cookie.requiredKugouSession().userId
        val raw = track.kugouRawAccess()
        val stalePurchase = raw != null && raw.classificationVersion < 5 && raw.paidOnly && !raw.needsVip
        raw?.takeUnless { stalePurchase }
            ?.let { return it.sourceAccess(track.access, accountId, hasVipAccess) }
        val value = KugouSourceMapping.track(track)
        val info = try {
            details(value, cookie).kugouStandardAccessDetail(value)?.kugouTrackAccessInfo()?.bindPurchase(accountId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { throw interrupted }
        catch (_: Exception) { null }
        if (info != null) return info.sourceAccess(track.access, accountId, hasVipAccess)
        // 旧购买结论可能来自下载权限；核对失败保留未知，不能继续把它当作确定的播放收费。
        return if (stalePurchase && track.access.badge == TrackAccessBadge.PURCHASE)
            track.access.copy(badge = null, trialAvailable = false) else track.access
    }
}

/** 响应必须属于原歌曲；其他音质的收费信息不能覆盖标准档的角标。 */
internal fun JSONObject.kugouStandardAccessDetail(track: MusicTrack): JSONObject? {
    if (optInt("status", 0) != 1 || optInt("error_code", 0) != 0) return null
    val roots = optJSONArray("data")?.let { rows ->
        (0 until rows.length()).mapNotNull(rows::optJSONObject)
    } ?: listOfNotNull(optJSONObject("data"))
    val hashes = (track.qualityIds.values + track.remoteId()).filter(String::isNotBlank)
    val root = roots.firstOrNull { row ->
        val hash = row.optString("hash").takeIf { it.isNotBlank() && it != "null" }
        val audioId = row.opt("album_audio_id")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
        val hashMatches = hash != null && hashes.any { it.equals(hash, ignoreCase = true) }
        val idMatches = track.catalogId.isNotBlank() && audioId == track.catalogId
        (hashMatches || idMatches) && (hash == null || hashMatches) &&
            (audioId == null || track.catalogId.isBlank() || idMatches)
    } ?: return null
    val candidates = buildList {
        add(root)
        root.optJSONArray("relate_goods")?.let { rows ->
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let(::add)
        }
    }
    return candidates.firstOrNull { row ->
        val quality = row.optString("quality").kugouQualityFromName() ?: row.optInt("level").kugouQualityFromLevel()
        val standardHash = track.kugouHashFor(AudioQuality.STANDARD)
        val hashMatches = standardHash == null || standardHash.equals(row.optString("hash"), ignoreCase = true)
        val audioId = row.opt("album_audio_id")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
        val idMatches = audioId == null || track.catalogId.isBlank() || audioId == track.catalogId
        idMatches && hashMatches && (quality == AudioQuality.STANDARD || (row === root && quality == null &&
            track.kugouHashFor(AudioQuality.STANDARD)?.equals(row.optString("hash"), ignoreCase = true) == true)
        )
    }
}

private fun requestKugouTrackAccess(track: MusicTrack, cookie: String): JSONObject {
    val session = cookie.requiredKugouSession()
    val params = linkedMapOf(
        "dfid" to session.dfid, "mid" to session.mid, "uuid" to "-",
        "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
        "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
        "token" to session.token, "userid" to session.userId,
    )
    val body = kugouResourceRequest(track, AudioQuality.STANDARD).toString()
    return JSONObject(PlatformHttp.postJson(
        KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v2/get_res_privilege/lite", params, body),
        body, cookie, kugouAndroidHeaders(params, "media.store.kugou.com"),
    ).text)
}

private fun JSONObject.kugouAccessInt(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) null else opt(name)?.toString()?.toDoubleOrNull()?.toInt()
}
