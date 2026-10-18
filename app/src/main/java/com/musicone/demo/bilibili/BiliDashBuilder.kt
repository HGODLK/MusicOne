package com.musicone.demo

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * B站单音频流 fMP4 按需纯音频 DASH 清单生成器与缓存键计算工具。
 */
internal object BiliDashBuilder {

    /**
     * 生成只包含单个选定音轨的纯音频 DASH 清单字符串。
     *
     * @param stream 选定的音频流
     * @param durationMs 音频时长毫秒数
     * @param isDolby 是否为杜比音轨
     */
    fun buildAudioOnlyManifest(
        stream: BiliAudioStream,
        durationMs: Long,
        isDolby: Boolean = false,
    ): String {
        val durationSeconds = durationMs.coerceAtLeast(1000L) / 1000.0
        val durationFormatted = String.format(Locale.US, "PT%.3fS", durationSeconds)
        val trackId = "bili_audio_${stream.id}"

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$durationFormatted" minBufferTime="PT1.5S">""").append('\n')
            append("""  <Period id="0" start="PT0S" duration="$durationFormatted">""").append('\n')
            append("""    <AdaptationSet id="1" contentType="audio" segmentAlignment="true" startWithSAP="1">""").append('\n')
            append("""      <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main" />""").append('\n')
            append("""      <Representation id="$trackId"""")
            append(""" bandwidth="${stream.bandwidth.coerceAtLeast(1L)}"""")
            append(""" mimeType="${xmlEscape(stream.mimeType)}"""")
            append(""" codecs="${xmlEscape(stream.codecs)}">""").append('\n')

            if (isDolby && stream.codecs.startsWith("ec-3", ignoreCase = true)) {
                append("""        <SupplementalProperty schemeIdUri="tag:dolby.com,2018:dash:EC3_ExtensionType:2018" value="JOC" />""").append('\n')
            }

            // 追加主 URL 与全部备用 URL 作为 BaseURL
            val urls = (listOf(stream.baseUrl) + stream.backupUrls)
                .filter { it.isNotBlank() }
                .distinct()
            urls.forEachIndexed { index, url ->
                append("""        <BaseURL serviceLocation="${xmlEscape(trackId)}_$index" priority="${index + 1}">${xmlEscape(url)}</BaseURL>""").append('\n')
            }

            append("""        <SegmentBase indexRange="${xmlEscape(stream.indexRange)}" indexRangeExact="true">""").append('\n')
            append("""          <Initialization range="${xmlEscape(stream.initializationRange)}" />""").append('\n')
            append("""        </SegmentBase>""").append('\n')
            append("""      </Representation>""").append('\n')
            append("""    </AdaptationSet>""").append('\n')
            append("""  </Period>""").append('\n')
            append("""</MPD>""").append('\n')
        }
    }

    /**
     * 将生成的 DASH MPD XML 包装为 data URI 格式。
     */
    fun toDataUri(manifestXml: String): String {
        val base64 = Base64.encodeToString(
            manifestXml.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        return "data:application/dash+xml;base64,$base64"
    }

    /**
     * 生成不依赖动态签名参数的稳定缓存键。
     *
     * 规则包含：B站账号命名空间、bvid+cid、音轨ID、codec。
     */
    fun buildCacheKey(
        accountMid: String,
        bvid: String,
        cid: Long,
        streamId: Int,
        codecs: String,
    ): String {
        val accountNamespace = if (accountMid.isNotBlank()) "mid-$accountMid" else "guest"
        val cleanCodec = codecs.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "bili-$accountNamespace-$bvid-$cid-track-$streamId-$cleanCodec"
    }

    /**
     * XML 字符转义。
     */
    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
}
