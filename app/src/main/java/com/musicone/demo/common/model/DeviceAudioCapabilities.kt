package com.musicone.demo

import android.media.MediaCodecList

internal data class DeviceAudioCapabilities(
    val supportsDolbyPlayback: Boolean,
) {
    companion object {
        fun detect(): DeviceAudioCapabilities = runCatching {
            val decoderTypes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .toList()
            DeviceAudioCapabilities(supportsDolbyPlayback = decoderTypes.supportsDolbyPlayback())
        }.getOrDefault(DeviceAudioCapabilities(supportsDolbyPlayback = false))
    }
}

/** Media3 允许 E-AC-3 解码器兼容 JOC 音源；可解码不代表当前输出已启用全景声。 */
internal fun Iterable<String>.supportsDolbyPlayback(): Boolean = any(::isDolbyAudioMime)
