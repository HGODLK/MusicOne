#ifndef NATIVE_AUDIO_AIFF_H
#define NATIVE_AUDIO_AIFF_H

#include <cstring>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
}

// 仅接受 AIFF/AIFC 容器中的整数 PCM，避免把同编码的 WAV 也分流到原生链路。
inline int nativeAudioAiffBitDepth(const AVFormatContext *context, const AVCodecParameters *parameters) {
    if (!context || !context->iformat || !context->iformat->name || !parameters ||
        std::strcmp(context->iformat->name, "aiff") != 0) {
        return 0;
    }

    int storageBitDepth;
    switch (parameters->codec_id) {
        case AV_CODEC_ID_PCM_S8:
            storageBitDepth = 8;
            break;
        case AV_CODEC_ID_PCM_S16BE:
        case AV_CODEC_ID_PCM_S16LE:
            storageBitDepth = 16;
            break;
        case AV_CODEC_ID_PCM_S24BE:
        case AV_CODEC_ID_PCM_S24LE:
            storageBitDepth = 24;
            break;
        case AV_CODEC_ID_PCM_S32BE:
        case AV_CODEC_ID_PCM_S32LE:
            storageBitDepth = 32;
            break;
        default:
            return 0;
    }

    // FFmpeg 的 24 位 PCM 解码结果装在 S32 中，源位深必须从编码参数读取。
    if (parameters->bits_per_raw_sample > 0 && parameters->bits_per_raw_sample <= storageBitDepth) {
        return parameters->bits_per_raw_sample;
    }
    if (parameters->bits_per_coded_sample > 0 && parameters->bits_per_coded_sample <= storageBitDepth) {
        return parameters->bits_per_coded_sample;
    }
    return storageBitDepth;
}

#endif // NATIVE_AUDIO_AIFF_H
