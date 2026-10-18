package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 核对歌曲和当前账号的实际信息；收费标签、空地址和接口异常都不能单独判死。 */
internal class TrackAvailabilityVerifier(context: Context) {
    private val preferences = PlatformPreferences(context.applicationContext)

    suspend fun verify(track: MusicTrack): String? = withContext(Dispatchers.IO) {
        try {
            val session = preferences.readSession(track.source)
            val credential = preferences.credential(track.source)
            runInterruptible {
                when (track.source) {
                    MusicSource.QQ -> {
                        val account = qqCredentialAccountId(credential)
                        val current = QqTrackAccessResolver().resolve(listOf(track), credential,
                            fresh = false, forceRefresh = true).single()
                        confirmedQqPlaybackRestriction(current.qqAccess, account,
                            QqEntitlements.membership(credential))
                    }
                    MusicSource.NETEASE -> {
                        val current = NeteaseApiClient { session.deviceId }
                            .songDetails(listOf(track.remoteId()), credential, session.account?.hasVipAccess == true)
                            .singleOrNull { it.id == track.id }
                        current?.takeUnless { it.playable }?.unavailableReason
                    }
                    MusicSource.KUGOU -> {
                        KugouApiClient().playbackSource(track, credential, AudioQuality.STANDARD)
                        null
                    }
                    else -> null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: TrackUnavailableException) {
            unavailable.message
        } catch (_: Exception) {
            null
        }
    }
}

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
