#include "native_audio_probe.h"
#include "native_audio_aiff.h"
#include "native_audio_io.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/dict.h>
}

#define LOG_TAG "NativeAudioProbe"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::string get_dict_entry(AVDictionary *dict, const char *key1, const char *key2 = nullptr) {
    AVDictionaryEntry *entry = av_dict_get(dict, key1, nullptr, AV_DICT_IGNORE_SUFFIX);
    if (!entry && key2) {
        entry = av_dict_get(dict, key2, nullptr, AV_DICT_IGNORE_SUFFIX);
    }
    return entry && entry->value ? std::string(entry->value) : "";
}

ProbeResult NativeAudioProbe::probe(NativeAudioIoBridge *ioBridge) {
    ProbeResult res;
    if (!ioBridge) {
        res.errorMessage = "无效的 IO Bridge";
        return res;
    }

    AVIOContext *avio = ioBridge->createAvioContext();
    if (!avio) {
        res.errorMessage = "无法创建 AVIOContext";
        return res;
    }

    AVFormatContext *fmt_ctx = avformat_alloc_context();
    if (!fmt_ctx) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        res.errorMessage = "无法分配 AVFormatContext";
        return res;
    }
    fmt_ctx->pb = avio;
    fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    int ret = avformat_open_input(&fmt_ctx, nullptr, nullptr, nullptr);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        res.errorMessage = std::string("无法打开输入流: ") + err;
        avformat_free_context(fmt_ctx);
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        return res;
    }

    ret = avformat_find_stream_info(fmt_ctx, nullptr);
    if (ret < 0) {
        res.errorMessage = "无法获取媒体流信息";
        avformat_close_input(&fmt_ctx);
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        return res;
    }

    res.containerName = fmt_ctx->iformat && fmt_ctx->iformat->name ? fmt_ctx->iformat->name : "";

    // 检查是否包含 DST 压缩（DFF 容器中常见）
    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_id == AV_CODEC_ID_DST) {
            res.errorMessage = "暂不支持 DST 压缩 DFF";
            avformat_close_input(&fmt_ctx);
            av_freep(&avio->buffer);
            avio_context_free(&avio);
            return res;
        }
    }

    int audioStreamIdx = av_find_best_stream(fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIdx < 0) {
        res.errorMessage = "未找到有效音频流";
        avformat_close_input(&fmt_ctx);
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        return res;
    }

    AVStream *audioStream = fmt_ctx->streams[audioStreamIdx];
    AVCodecParameters *codecpar = audioStream->codecpar;
    const AVCodec *codec = avcodec_find_decoder(codecpar->codec_id);
    res.codecName = codec && codec->name ? codec->name : "";

    // 格式识别与分类
    bool recognized = false;
    const int aiffBitDepth = nativeAudioAiffBitDepth(fmt_ctx, codecpar);
    if (aiffBitDepth > 0 && codec) {
        res.formatName = "AIFF";
        res.sourceBitDepth = aiffBitDepth;
        recognized = true;
    } else if (codecpar->codec_id == AV_CODEC_ID_APE) {
        res.formatName = "APE";
        res.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        recognized = true;
    } else if (codecpar->codec_id == AV_CODEC_ID_ALAC) {
        res.formatName = "ALAC";
        res.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        recognized = true;
    } else if (codecpar->codec_id == AV_CODEC_ID_WAVPACK) {
        res.formatName = "WavPack";
        res.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        // 检查 Hybrid 模式：读取文件头标志位
        if (ioBridge) {
            uint8_t hdr[32];
            ioBridge->seek(0, SEEK_SET);
            int readBytes = ioBridge->read(hdr, 32);
            if (readBytes == 32 && memcmp(hdr, "wvpk", 4) == 0) {
                uint32_t flags = hdr[24] | (hdr[25] << 8) | (hdr[26] << 16) | (hdr[27] << 24);
                if (flags & 8) { // WV_HYBRID_FLAG
                    res.isWavPackHybrid = true;
                    res.errorMessage = "暂不支持 WavPack Hybrid 模式";
                    avformat_close_input(&fmt_ctx);
                    av_freep(&avio->buffer);
                    avio_context_free(&avio);
                    return res;
                }
            }
        }
        recognized = true;
    } else if (codecpar->codec_id == AV_CODEC_ID_DSD_LSBF ||
               codecpar->codec_id == AV_CODEC_ID_DSD_MSBF ||
               codecpar->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
               codecpar->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR) {
        res.isDsd = true;
        res.sourceBitDepth = 1;
        // 判断 DSD 等级：以基准采样率 44100 为准，或者看实际 DSD 比特率 / 采样率
        int64_t dsdRate = codecpar->sample_rate;
        // FFmpeg 解封装 dsf 时，sample_rate 经常报告为 352800 (即 8 * 44100) 或原始比特率 2822400 (64 * 44100)
        if (dsdRate < 1000000) {
            dsdRate = dsdRate * 8; // 8x 字节采样率转 1-bit 采样率
        }
        int multiplier = static_cast<int>(dsdRate / 44100);
        if (multiplier >= 512) {
            res.dsdRateMultiplier = 512;
            res.formatName = "DSD512";
        } else if (multiplier >= 256) {
            res.dsdRateMultiplier = 256;
            res.formatName = "DSD256";
        } else if (multiplier >= 128) {
            res.dsdRateMultiplier = 128;
            res.formatName = "DSD128";
        } else {
            res.dsdRateMultiplier = 64;
            res.formatName = "DSD64";
        }
        recognized = true;
    }

    if (!recognized) {
        res.errorMessage = "不支持的音频编码格式: " + res.codecName;
        avformat_close_input(&fmt_ctx);
        av_freep(&avio->buffer);
        avio_context_free(&avio);
        return res;
    }

    res.sampleRate = codecpar->sample_rate;
    res.channelCount = codecpar->ch_layout.nb_channels > 0 ? codecpar->ch_layout.nb_channels : codecpar->channels;

    // 时长计算
    if (fmt_ctx->duration != AV_NOPTS_VALUE && fmt_ctx->duration > 0) {
        res.durationMs = fmt_ctx->duration / (AV_TIME_BASE / 1000);
    } else if (audioStream->duration != AV_NOPTS_VALUE && audioStream->time_base.den > 0) {
        res.durationMs = av_rescale_q(audioStream->duration, audioStream->time_base, {1, 1000});
    }

    // 标签解析（先从音频流取，再从容器取）
    AVDictionary *meta = audioStream->metadata ? audioStream->metadata : fmt_ctx->metadata;
    res.title = get_dict_entry(meta, "title", "TITLE");
    if (res.title.empty() && fmt_ctx->metadata) {
        res.title = get_dict_entry(fmt_ctx->metadata, "title", "TITLE");
    }
    res.artist = get_dict_entry(meta, "artist", "ARTIST");
    if (res.artist.empty() && fmt_ctx->metadata) {
        res.artist = get_dict_entry(fmt_ctx->metadata, "artist", "ARTIST");
    }
    res.album = get_dict_entry(meta, "album", "ALBUM");
    if (res.album.empty() && fmt_ctx->metadata) {
        res.album = get_dict_entry(fmt_ctx->metadata, "album", "ALBUM");
    }

    // 封面提取（查找 AV_DISPOSITION_ATTACHED_PIC 流）
    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            AVPacket *pkt = &fmt_ctx->streams[i]->attached_pic;
            if (pkt && pkt->size > 0 && pkt->size <= 10 * 1024 * 1024) { // 限制 10MB
                res.coverData.assign(pkt->data, pkt->data + pkt->size);
                // 探测 MIME 类型
                if (pkt->size >= 3 && pkt->data[0] == 0xFF && pkt->data[1] == 0xD8 && pkt->data[2] == 0xFF) {
                    res.coverMime = "image/jpeg";
                } else if (pkt->size >= 8 && memcmp(pkt->data, "\x89PNG\r\n\x1a\n", 8) == 0) {
                    res.coverMime = "image/png";
                } else {
                    res.coverMime = "image/*";
                }
                break;
            }
        }
    }

    res.success = true;

    avformat_close_input(&fmt_ctx);
    av_freep(&avio->buffer);
    avio_context_free(&avio);
    return res;
}
