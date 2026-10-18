package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 哔哩哔哩音源播放解析适配器。
 *
 * 遵循 [PlatformPlaybackAdapter] 接口规范，只返回纯音频 DASH 清单（仅含选定音轨），
 * 并根据设备能力策略 [DolbyPlaybackPolicy] 自动匹配杜比/Hi-Res/普通音质。
 */
internal class BilibiliPlaybackAdapter(context: Context) : PlatformPlaybackAdapter {
    override val source: MusicSource = MusicSource.BILIBILI

    private val api = BiliApi(context.applicationContext)
    private val webSession = BiliWebSession(context.applicationContext)
    private val sessionStore = BiliSessionStore(context.applicationContext)

    override suspend fun enrichTrack(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) {
        if (track.bilibiliCid > 0L && track.durationMs > 0L && track.bilibiliBvid.isNotBlank()) {
            return@withContext track
        }
        val bvid = track.bilibiliBvid.ifBlank {
            BiliTrackMapper.resolveBvid(track.id.removePrefix("bilibili-"))
        } ?: return@withContext track

        val pages = api.getVideoPages(bvid, sessionStore.read()?.cookie.orEmpty())
        require(pages.isNotEmpty()) { "视频分 P 信息为空" }

        val targetPart = if (track.bilibiliCid > 0L) {
            pages.firstOrNull { it.cid == track.bilibiliCid } ?: error("此分 P 已不存在")
        } else {
            pages.first()
        }

        track.copy(
            bilibiliBvid = bvid,
            bilibiliCid = targetPart.cid,
            durationMs = if (targetPart.durationMs > 0L) targetPart.durationMs else track.durationMs,
        )
    }

    override suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource = resolveAudio(track, quality, automatic = false)

    override suspend fun resolveAutomatic(track: MusicTrack, quality: AudioQuality, verifyMedia: Boolean): PlaybackSource =
        resolveAudio(track, quality, automatic = true)

    private suspend fun resolveAudio(track: MusicTrack, quality: AudioQuality, automatic: Boolean): PlaybackSource = withContext(Dispatchers.IO) {
        val readyTrack = if (track.bilibiliCid <= 0L || track.bilibiliBvid.isBlank()) {
            enrichTrack(track)
        } else {
            track
        }

        val bvid = readyTrack.bilibiliBvid.ifBlank {
            throw PlatformApiException("缺少有效的B站视频BV号")
        }
        val cid = readyTrack.bilibiliCid.takeIf { it > 0L } ?: throw PlatformApiException("缺少有效的B站分P(CID)")

        val session = sessionStore.read()
        val cookie = session?.cookie.orEmpty()
        val audioResult = api.getAudioStreams(bvid, cid, cookie)

        val selected = selectBiliAudio(audioResult, quality, automatic, DolbyPlaybackPolicy.eligibility)
        val stream = selected.stream
        val actualQuality = selected.quality
        val isDolby = selected.isDolby

        // 构造稳定缓存键（不带签名参数）
        val cacheKey = BiliDashBuilder.buildCacheKey(
            accountMid = session?.mid.orEmpty(),
            bvid = bvid,
            cid = cid,
            streamId = stream.id,
            codecs = stream.codecs,
        )

        val requestHeaders = webSession.headers(cookie, "https://www.bilibili.com/video/$bvid")

        // 注册主 URL 和 CDN 备选 URL 供 Media3 数据源注入请求头和缓存身份
        val allUrls = (listOf(stream.baseUrl) + stream.backupUrls).filter { it.isNotBlank() }.distinct()
        allUrls.forEach { streamUrl ->
            SupplementalPlaybackRequests.register(streamUrl, requestHeaders, cacheKey)
        }

        // 构建纯音频 DASH 清单（只含选定音轨）
        val manifestXml = BiliDashBuilder.buildAudioOnlyManifest(
            stream = stream,
            durationMs = audioResult.durationMs,
            isDolby = isDolby,
        )
        val dataUri = BiliDashBuilder.toDataUri(manifestXml)
        check(sessionStore.read() == session) { "B站账号已变化，请重新播放" }
        val format = stream.codecs.ifBlank { stream.mimeType }
        val actualFormat = biliStreamCodecLabel(stream, isDolby)

        return@withContext PlaybackSource(
            url = dataUri,
            requestedQuality = quality,
            actualQuality = actualQuality,
            bitRate = stream.bandwidth.toInt(),
            format = format,
            trial = false,
            actualFormat = actualFormat,
            verificationPending = false,
            requestHeaders = requestHeaders,
            cacheKey = cacheKey,
            mimeType = "application/dash+xml",
        )
    }

    override suspend fun availableQualities(
        track: MusicTrack,
        onProgress: (List<AudioQuality>) -> Unit,
    ): List<AudioQuality> = withContext(Dispatchers.IO) {
        val readyTrack = if (track.bilibiliCid <= 0L || track.bilibiliBvid.isBlank()) {
            enrichTrack(track)
        } else {
            track
        }
        val bvid = readyTrack.bilibiliBvid.takeIf(String::isNotBlank) ?: error("缺少 BV 号")
        val cid = readyTrack.bilibiliCid.takeIf { it > 0L } ?: error("缺少分 P 身份")
        val cookie = sessionStore.read()?.cookie.orEmpty()
        val audioResult = api.getAudioStreams(bvid, cid, cookie)

        val result = biliAvailableQualities(audioResult, DolbyPlaybackPolicy.eligibility)
        onProgress(result)
        result
    }

    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = emptyList()
}
