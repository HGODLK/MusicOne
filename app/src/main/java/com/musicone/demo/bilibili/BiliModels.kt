package com.musicone.demo

/**
 * B站扫码生成结果。
 */
internal data class BiliQrCode(
    val url: String,
    val qrcodeKey: String,
)

/**
 * B站扫码轮询状态。
 *
 * @param code 0: 成功, 86101: 未扫码, 86090: 已扫码未确认, 86038: 二维码已过期
 */
internal data class BiliQrStatus(
    val code: Int,
    val message: String,
    val cookie: String? = null,
)

/**
 * 极验验证码挑战信息。
 */
internal data class BiliGeetestChallenge(
    val gt: String,
    val challenge: String,
    val token: String = "",
    val requestGeneration: Long = -1L,
)

/**
 * 极验验证完成后回传的数据。
 */
internal data class BiliGeetestResult(
    val challenge: String,
    val validate: String,
    val seccode: String,
    val token: String = "",
)

/**
 * 发送短信验证码的结果。
 */
internal sealed interface BiliSmsSendResult {
    data class Success(val captchaKey: String) : BiliSmsSendResult
    data class NeedGeetest(val challenge: BiliGeetestChallenge) : BiliSmsSendResult
    data class Failed(val message: String, val code: Int = -1) : BiliSmsSendResult
}

/**
 * 收藏夹简要信息。
 */
internal data class BiliFavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
    val cover: String = "",
)

/**
 * 收藏夹内视频条目。
 */
internal data class BiliVideoItem(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val cover: String,
    val upName: String,
    val durationSec: Long,
    val isInvalid: Boolean = false,
)

/**
 * 收藏夹分页拉取结果。
 */
internal data class BiliFavVideoPage(
    val videos: List<BiliVideoItem>,
    val hasMore: Boolean,
)

/**
 * 视频的一个分 P 信息。
 */
internal data class BiliPagePart(
    val cid: Long,
    val page: Int,
    val part: String,
    val durationMs: Long,
)

/**
 * 视频详情（含完整真实标题、封面、UP主信息及分P）。
 */
internal data class BiliVideoDetails(
    val aid: Long,
    val bvid: String,
    val title: String,
    val cover: String,
    val upName: String,
    val upMid: Long = 0L,
    val durationSec: Long = 0L,
    val pages: List<BiliPagePart> = emptyList(),
)

/**
 * DASH 格式中的单条音频流。
 */
internal data class BiliAudioStream(
    val id: Int,
    val baseUrl: String,
    val backupUrls: List<String> = emptyList(),
    val bandwidth: Long = 0L,
    val codecs: String = "mp4a.40.2",
    val mimeType: String = "audio/mp4",
    val initializationRange: String = "",
    val indexRange: String = "",
)

/**
 * 视频播放地址解析得到的音频信息。
 */
internal data class BiliPlayAudioResult(
    val defaultAudio: BiliAudioStream?,
    val hiResAudio: BiliAudioStream?,
    val dolbyAudio: BiliAudioStream?,
    val durationMs: Long,
)
