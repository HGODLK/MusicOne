package com.musicone.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 解码器报告当前流的参数；FLAC 文件头补充编码位深。 */
internal object PlaybackAudioInfo {
    private val validated = MutableStateFlow<Pair<String, Boolean>?>(null)
    val validation = validated.asStateFlow()
    fun verify(url: String, full: Boolean) { validated.value = url to full }
    private val mutable = MutableStateFlow<Pair<String, String>?>(null)
    val current = mutable.asStateFlow()
    private val headers = object : LinkedHashMap<String, String>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }
    @Synchronized fun remember(url: String, description: String) {
        headers[url] = description
        if (mutable.value?.first == url) mutable.value = url to description
    }
    @Synchronized fun publish(url: String, mime: String?, sampleRate: Int, bitrate: Int) {
        val codec = when (mime) { "audio/mpeg" -> "MP3"; "audio/flac" -> "FLAC"; "audio/mp4a-latm" -> "AAC"; else -> mime?.removePrefix("audio/")?.uppercase() ?: "音频" }
        val rate = if (sampleRate > 0) "${java.math.BigDecimal(sampleRate).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz" else null
        mutable.value = url to (headers[url] ?: listOfNotNull(codec, bitrate.takeIf { it > 0 }?.let { "${it / 1000}kbps" }, rate).joinToString(" · "))
    }
    fun clear() { mutable.value = null; validated.value = null }
}
