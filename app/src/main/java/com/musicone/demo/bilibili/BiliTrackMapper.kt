package com.musicone.demo

import java.net.URI

/**
 * B站链接解析与 MusicTrack 映射工具。
 */
internal object BiliTrackMapper {
    private val BV_REGEX = Regex("""(?i)\b(BV1[a-zA-Z0-9]{9})\b""")
    private val PAGE_PARAM_REGEX = Regex("""(?i)[?&]p=(\d+)""")

    fun expandLink(input: String): String {
        val link = Regex("https?://b23\\.tv/[^\\s]+|b23\\.tv/[^\\s]+").find(input)?.value ?: return input
        val response = PlatformHttp.get(if (link.startsWith("http")) link else "https://$link", followRedirects = false)
        return response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull() ?: input
    }

    /**
     * 从输入的文本或链接中提取指定分 P 序号（如 ?p=2 或 &p=3 返回对应整数）。
     */
    fun resolvePageNumber(input: String): Int? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return PAGE_PARAM_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 从输入的文本中提取 BV 号。
     * 支持纯 BV 号、包含 BV 号的长链接、以及 b23.tv 短链（通过解析重定向地址）。
     */
    fun resolveBvid(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 优先正则匹配直接存在的 BV 号
        BV_REGEX.find(trimmed)?.groupValues?.get(1)?.let {
            return normalizeBvid(it)
        }

        // 判断是否为 b23 短链并尝试解析重定向
        if (trimmed.contains("b23.tv", ignoreCase = true)) {
            val url = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return resolveShortLink(url)
        }

        return null
    }

    /**
     * 规范化 BV 号（保证以大写 BV 开头）。
     */
    private fun normalizeBvid(bvid: String): String {
        return "BV" + bvid.substring(2)
    }

    /**
     * 请求短链获取 302 重定向后的 Location 从而解析出真实的 BV 号。
     */
    private fun resolveShortLink(shortUrl: String): String? {
        return runCatching {
            val resp = PlatformHttp.get(shortUrl, followRedirects = false)
            val location = resp.headers.entries
                .firstOrNull { it.key.equals("Location", ignoreCase = true) }
                ?.value?.firstOrNull()
            if (!location.isNullOrBlank()) {
                BV_REGEX.find(location)?.groupValues?.get(1)?.let { normalizeBvid(it) }
            } else {
                // 若重定向已被自动跟随或未带 Location，再尝试从响应文本中匹配
                BV_REGEX.find(resp.text)?.groupValues?.get(1)?.let { normalizeBvid(it) }
            }
        }.getOrNull()
    }

    /**
     * 将视频的单 P 信息转换为系统统一的 MusicTrack 模型。
     *
     * @param video 所属视频基本信息
     * @param part 该分 P 信息
     * @param multiParts 是否包含多个分 P（用于决定标题展示）
     */
    fun toTrack(
        video: BiliVideoItem,
        part: BiliPagePart,
        multiParts: Boolean,
    ): MusicTrack {
        val trackId = "bilibili-${video.bvid}_${part.cid}"
        val trackTitle = if (multiParts && part.part.isNotBlank() && part.part != video.title) {
            "${video.title} - ${part.part}"
        } else {
            video.title.ifBlank { part.part.ifBlank { "B站音频" } }
        }

        val isPlayable = !video.isInvalid && part.cid > 0L
        val reason = if (!isPlayable) {
            if (video.title.contains("失效") || video.isInvalid) "视频已失效或已被删除" else "暂无可用分P音频"
        } else {
            null
        }

        return MusicTrack(
            id = trackId,
            source = MusicSource.BILIBILI,
            title = trackTitle,
            artists = video.upName.ifBlank { "哔哩哔哩" },
            album = video.title,
            durationMs = if (part.durationMs > 0L) part.durationMs else video.durationSec * 1000L,
            artworkStart = 0L,
            artworkEnd = 0L,
            artworkMark = "",
            previewUrl = "",
            artworkUrl = video.cover.takeIf { it.isNotBlank() },
            playable = isPlayable,
            unavailableReason = reason,
            bilibiliBvid = video.bvid,
            bilibiliCid = part.cid,
            catalogId = video.aid.toString(),
        )
    }

    /**
     * 为单视频条目创建兜底 MusicTrack（用于尚未展开分 P 或分 P 接口失败时保留记录）。
     */
    fun toFallbackTrack(video: BiliVideoItem): MusicTrack {
        val trackId = "bilibili-${video.bvid.ifBlank { "av${video.aid}" }}_${video.cid}"
        val isPlayable = !video.isInvalid && video.bvid.isNotBlank()
        val reason = if (!isPlayable) "视频已失效或已被删除" else null

        return MusicTrack(
            id = trackId,
            source = MusicSource.BILIBILI,
            title = video.title.ifBlank { "B站音频" },
            artists = video.upName.ifBlank { "哔哩哔哩" },
            album = video.title,
            durationMs = video.durationSec * 1000L,
            artworkStart = 0L,
            artworkEnd = 0L,
            artworkMark = "",
            previewUrl = "",
            artworkUrl = video.cover.takeIf { it.isNotBlank() },
            playable = isPlayable,
            unavailableReason = reason,
            bilibiliBvid = video.bvid,
            bilibiliCid = video.cid,
            catalogId = video.aid.toString(),
        )
    }
}
