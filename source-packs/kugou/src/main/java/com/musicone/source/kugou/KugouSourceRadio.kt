package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 对接已有 next 消费入口，反馈只有宿主显式调用时才发送。 */
internal class KugouSourceRadio(
    private val state: () -> KugouPackageState,
    private val client: KugouRadioClient = KugouRadioClient(),
) : SourceRadio {
    override suspend fun next(previous: SourcePayload?): SourceRadioBatch {
        val owner = state()
        return owner.request { session ->
            val first = kugouRadioFirstRequest(previous, session.cookie)
            val account = owner.account()
            val response = client.request(session.cookie, if (first) "login" else "play")
            currentCoroutineContext().ensureActive()
            owner.check(session)
            SourceRadioBatch(response.kugouRadioTracks(state().verifiedVipAccess(session), account?.userId),
                SourcePayload("kugou.radio", 1, JSONObject().put("owner", KugouProtocol.md5(session.cookie)).toString()))
        }
    }

    override suspend fun feedback(track: SourceTrackDetails, action: String) {
        val wireAction = kugouRadioFeedbackAction(action)
        val owner = state()
        owner.request { session ->
            require(track.track.identity.sourceId == KUGOU_SOURCE_ID &&
                track.track.identity.accountId == owner.account()?.userId) { "猜你喜欢歌曲不属于当前酷狗账号" }
            val hash = track.track.opaqueData["kugou.radio.hash"]
                ?: throw PlatformApiException("猜你喜欢歌曲缺少反馈所需的歌曲信息")
            currentCoroutineContext().ensureActive()
            owner.check(session)
            client.request(session.cookie, wireAction, hash,
                track.track.opaqueData["kugou.radio.songid"], track.track.opaqueData["kugou.radio.cur_mark"])
            Unit
        }
    }
}
