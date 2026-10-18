package com.musicone.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已解析的格式先显示，解码器参数到达后补齐；迟到结果只更新自己的缓存。 */
internal object PlaybackAudioInfo {
    private data class Details(val header: String? = null, val mergeDecoded: Boolean = false,
        val hintedBitrate: Int = 0, val codec: String? = null, val sampleRate: Int = 0,
        val bitrate: Int = 0, val bitDepth: Int = 0)
    private val details = object : LinkedHashMap<String, Details>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Details>?) = size > 32
    }
    private var activeUrl: String? = null
    private val validated = MutableStateFlow<Pair<String, Boolean>?>(null)
    val validation = validated.asStateFlow()
    private val mutable = MutableStateFlow<Pair<String, String>?>(null)
    val current = mutable.asStateFlow()

    @Synchronized fun activate(url: String?) {
        activeUrl = url
        validated.value = null
        mutable.value = url?.let { value -> details[value]?.let { value to describe(it) } }
    }

    @Synchronized fun verify(url: String, full: Boolean) {
        if (activeUrl == url) validated.value = url to full
    }

    @Synchronized fun remember(url: String, description: String, mergeDecoded: Boolean = false,
        bitrate: Int = 0) {
        details[url] = (details[url] ?: Details()).copy(header = description,
            mergeDecoded = mergeDecoded, hintedBitrate = bitrate)
        refresh(url)
    }

    @Synchronized fun publish(url: String, mime: String?, sampleRate: Int, bitrate: Int,
        bitDepth: Int = 0) {
        if (activeUrl != url) return
        val previous = details[url] ?: Details()
        details[url] = previous.copy(codec = codecLabel(mime, url) ?: previous.codec,
            sampleRate = sampleRate.takeIf { it > 0 } ?: previous.sampleRate,
            bitrate = bitrate.takeIf { it > 0 } ?: previous.bitrate,
            bitDepth = bitDepth.takeIf { it > 0 } ?: previous.bitDepth)
        refresh(url)
    }

    private fun refresh(url: String) {
        if (activeUrl == url) details[url]?.let { mutable.value = url to describe(it) }
    }

    private fun describe(info: Details): String {
        if (!info.mergeDecoded && info.header != null) return info.header
        val codec = if (info.header == "Dolby Atmos") {
            listOfNotNull(info.header, info.codec).distinct().joinToString(" · ")
        } else info.codec ?: info.header ?: "音频"
        val bitrate = (info.bitrate.takeIf { it > 0 } ?: info.hintedBitrate)
            .takeIf { it >= 1000 }?.let { "${it / 1000}kbps" }
        val rate = info.sampleRate.takeIf { it > 0 }?.let {
            "${java.math.BigDecimal(it).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
        }
        val precision = if (info.bitDepth > 0) {
            listOfNotNull("${info.bitDepth}bit", rate).joinToString(" / ")
        } else rate
        return listOfNotNull(codec, bitrate, precision).joinToString(" · ")
    }

    private fun codecLabel(mime: String?, url: String): String? = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/mp4a-latm" -> "AAC"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/ac3" -> "AC-3"
        "audio/opus" -> "Opus"
        "audio/raw" -> when {
            url.substringBefore('?').endsWith(".dsf", true) || url.substringBefore('?').endsWith(".dff", true) -> "DSD"
            url.substringBefore('?').endsWith(".ape", true) -> "APE"
            url.substringBefore('?').endsWith(".wv", true) -> "WavPack"
            url.substringBefore('?').endsWith(".m4a", true) -> "ALAC"
            else -> "无损PCM"
        }
        else -> mime?.removePrefix("audio/")?.uppercase()
    }

    @Synchronized fun clear() { activate(null) }
}
