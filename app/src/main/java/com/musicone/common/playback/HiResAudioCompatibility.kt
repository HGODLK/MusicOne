package com.musicone

import android.content.Context
import android.media.AudioManager
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
    var lgAudioSink: LgHiResAudioSink? = null
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
        // 系统混音路径统一成主输出采样率：每首交给 AudioTrack 的格式相同，切歌与自然过渡都不重建；比特完美、USB 独占、LG 原生原样透传
        val fixedRate = FixedRateAudioSink(
            delegate,
            targetRate = { systemOutputSampleRate(context) },
            bypass = {
                val route = UsbAudioOutputStatus.current.value.route
                route == UsbAudioOutputRoute.SYSTEM_BIT_PERFECT || route == UsbAudioOutputRoute.DIRECT_UAC2 ||
                    PlaybackOutputStatus.lg.value != null
            },
        )
        val usb = MusicOneUsbAudioSink(fixedRate, context, PlaybackOptions.usbLosslessOutput).also {
            usbAudioSink = it
        }
        val output = if (isLgAudioDevice(android.os.Build.MANUFACTURER)) {
            LgHiResAudioSink(context, usb).also { lgAudioSink = it }
        } else usb
        // 自然过渡包在最外层，没触发过渡时原样往下传
        return AutoMixAudioSink(output, seamlessFormatChange = { fixedRate.normalizing }).also { AutoMixEngine.sink = it }
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
            usbAudioSink = usbAudioSink,
            lgAudioSink = lgAudioSink,
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
    private val lgAudioSink: LgHiResAudioSink?,
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
        usbAudioSink?.setSourceFormat(formatHolder.format)
        lgAudioSink?.setSourceFormat(formatHolder.format)
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

/** 系统主输出（混音器）采样率；读不到或明显异常时返回 null，输出层原样透传。 */
private fun systemOutputSampleRate(context: Context): Int? =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8_000..768_000 }

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

/** 取音频原始的有效位深，避免把解码器输出的 32-bit PCM 容器误当成源文件位深。 */
@OptIn(UnstableApi::class)
internal fun sourceAudioBitDepth(format: Format?): Int {
    val label = format?.label
    if (label != null && label.startsWith("effective-depth:")) {
        val depth = label.removePrefix("effective-depth:").toIntOrNull()
        if (depth != null && depth in setOf(16, 24, 32)) {
            return depth
        }
    }
    if (format?.sampleMimeType == MimeTypes.AUDIO_FLAC) {
        format.initializationData.firstNotNullOfOrNull { flacMediaInfo(it)?.bitDepth }
            ?.takeIf { it in setOf(16, 24, 32) }?.let { return it }
    }
    return when (format?.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }
}
