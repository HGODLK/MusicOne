package com.musicone.demo

import android.media.MediaCodecList

internal data class DeviceAudioCapabilities(
    val supportsDolbyAtmos: Boolean,
) {
    companion object {
        fun detect(): DeviceAudioCapabilities = runCatching {
            val decoderTypes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .toList()
            DeviceAudioCapabilities(supportsDolbyAtmos = decoderTypes.supportsDolbyAtmos())
        }.getOrDefault(DeviceAudioCapabilities(supportsDolbyAtmos = false))
    }
}

internal fun Iterable<String>.supportsDolbyAtmos(): Boolean =
    any { it.equals(DOLBY_ATMOS_MIME, ignoreCase = true) }

private const val DOLBY_ATMOS_MIME = "audio/eac3-joc"
