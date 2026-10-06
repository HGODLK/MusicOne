package com.musicone.demo

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 部分厂商 FLAC 解码器虽然声明支持高解析音频，但输入缓冲过小。
 * FLAC 优先使用系统通用软件解码器，并为合法的大帧预留足够空间。
 */
@OptIn(UnstableApi::class)
internal class HiResCompatibleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    val routingProvider = RoutingAudioTrackProvider()
    var usbAudioSink: MusicOneUsbAudioSink? = null
        private set

    init {
        setEnableDecoderFallback(true)
        setMediaCodecSelector(
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) {
                    MediaCodecSelector.PREFER_SOFTWARE
                } else {
                    MediaCodecSelector.DEFAULT
                }
                selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .let { decoders ->
                        if (mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) {
                            decoders.sortedBy { decoder -> hiResAudioDecoderRank(decoder.name) }
                        } else {
                            decoders
                        }
                    }
            },
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        @Suppress("DEPRECATION")
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioTrackProvider(routingProvider)
            .build()
        return MusicOneUsbAudioSink(delegate, context, PlaybackOptions.usbLosslessOutput).also {
            usbAudioSink = it
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val firstRendererIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        val mediaCodecRendererIndex = (firstRendererIndex until out.size).firstOrNull { index ->
            out[index] is MediaCodecAudioRenderer
        } ?: return
        out[mediaCodecRendererIndex] = HiResMediaCodecAudioRenderer(
            context = context,
            codecAdapterFactory = getCodecAdapterFactory(),
            mediaCodecSelector = mediaCodecSelector,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink,
            usbAudioSink = audioSink as? MusicOneUsbAudioSink,
        )
    }
}

@OptIn(UnstableApi::class)
private class HiResMediaCodecAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val usbAudioSink: MusicOneUsbAudioSink?,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        usbAudioSink?.setSourceBitDepth(sourceAudioBitDepth(formatHolder.format))
        return super.onInputFormatChanged(formatHolder)
    }

    override fun getCodecMaxInputSize(
        codecInfo: MediaCodecInfo,
        format: Format,
        streamFormats: Array<out Format>,
    ): Int = hiResCodecMaxInputSize(
        sampleMimeType = format.sampleMimeType,
        defaultMaxInputSize = super.getCodecMaxInputSize(codecInfo, format, streamFormats),
    )
}

internal const val HI_RES_FLAC_MAX_INPUT_SIZE_BYTES = 512 * 1024

internal fun hiResAudioDecoderRank(name: String): Int = when {
    name.startsWith("c2.android.", ignoreCase = true) -> 0
    name.startsWith("OMX.google.", ignoreCase = true) -> 1
    name.contains("google", ignoreCase = true) -> 1
    else -> 2
}

internal fun hiResCodecMaxInputSize(sampleMimeType: String?, defaultMaxInputSize: Int): Int =
    if (sampleMimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) {
        maxOf(defaultMaxInputSize, HI_RES_FLAC_MAX_INPUT_SIZE_BYTES)
    } else {
        defaultMaxInputSize
    }

/** 使用提取器报告的有效位深，避免把解码器的 32-bit PCM 容器误当成源文件位深。 */
internal fun sourceAudioBitDepth(format: Format?): Int = when (format?.pcmEncoding) {
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
    else -> 16
}
