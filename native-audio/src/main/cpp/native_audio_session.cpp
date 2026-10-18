#include "native_audio_session.h"
#include "native_audio_aiff.h"
#include "native_audio_resample_quality.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "NativeAudioSession"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static int session_interrupt_callback(void *opaque) {
    auto *session = static_cast<NativeAudioSession *>(opaque);
    return session && session->isCancelled() ? 1 : 0;
}

NativeAudioSession::NativeAudioSession(NativeAudioIoBridge *ioBridge, const SessionConfig &config)
    : ioBridge_(ioBridge), config_(config) {
    packet_ = av_packet_alloc();
    decoded_frame_ = av_frame_alloc();
}

NativeAudioSession::~NativeAudioSession() {
    close();
    if (packet_) {
        av_packet_free(&packet_);
    }
    if (decoded_frame_) {
        av_frame_free(&decoded_frame_);
    }
    // 会话唯一持有 IO 桥；在解码上下文退出后释放其 JNI 全局引用。
    delete ioBridge_;
    ioBridge_ = nullptr;
}

void NativeAudioSession::cancel() {
    cancelled_.store(true);
    if (ioBridge_) {
        ioBridge_->cancel();
    }
}

void NativeAudioSession::close() {
    cancel();
    std::lock_guard<std::mutex> lock(mutex_);

    if (swr_ctx_) {
        swr_free(&swr_ctx_);
    }
    if (codec_ctx_) {
        avcodec_free_context(&codec_ctx_);
    }
    if (fmt_ctx_) {
        avformat_close_input(&fmt_ctx_);
    }
    if (avio_) {
        av_freep(&avio_->buffer);
        avio_context_free(&avio_);
    }
    pcm_staging_.clear();
    staging_offset_ = 0;
}

bool NativeAudioSession::open(std::string &errorOut) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ioBridge_) {
        errorOut = "无效的数据源 IO Bridge";
        return false;
    }

    avio_ = ioBridge_->createAvioContext();
    if (!avio_) {
        errorOut = "无法创建 AVIOContext";
        return false;
    }

    fmt_ctx_ = avformat_alloc_context();
    if (!fmt_ctx_) {
        errorOut = "无法分配 AVFormatContext";
        return false;
    }
    fmt_ctx_->pb = avio_;
    fmt_ctx_->flags |= AVFMT_FLAG_CUSTOM_IO;
    fmt_ctx_->interrupt_callback.callback = session_interrupt_callback;
    fmt_ctx_->interrupt_callback.opaque = this;

    int ret = avformat_open_input(&fmt_ctx_, nullptr, nullptr, nullptr);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        errorOut = std::string("无法打开媒体流: ") + err;
        return false;
    }

    ret = avformat_find_stream_info(fmt_ctx_, nullptr);
    if (ret < 0) {
        errorOut = "无法解析流信息";
        return false;
    }

    // 检查 DST 压缩
    for (unsigned int i = 0; i < fmt_ctx_->nb_streams; i++) {
        if (fmt_ctx_->streams[i]->codecpar->codec_id == AV_CODEC_ID_DST) {
            errorOut = "暂不支持 DST 压缩 DFF";
            return false;
        }
    }

    audio_stream_idx_ = av_find_best_stream(fmt_ctx_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audio_stream_idx_ < 0) {
        errorOut = "未找到有效音频流";
        return false;
    }

    audio_stream_ = fmt_ctx_->streams[audio_stream_idx_];
    AVCodecParameters *codecpar = audio_stream_->codecpar;
    const AVCodec *codec = avcodec_find_decoder(codecpar->codec_id);
    if (!codec) {
        errorOut = "未找到解码器";
        return false;
    }

    codec_ctx_ = avcodec_alloc_context3(codec);
    if (!codec_ctx_) {
        errorOut = "无法分配解码上下文";
        return false;
    }

    ret = avcodec_parameters_to_context(codec_ctx_, codecpar);
    if (ret < 0) {
        errorOut = "无法配置解码参数";
        return false;
    }

    // 解码线程数：根据规范第 9 节，原生解码线程数从 1 开始，不占用过多 CPU 抢占动画
    codec_ctx_->thread_count = 1;

    ret = avcodec_open2(codec_ctx_, codec, nullptr);
    if (ret < 0) {
        errorOut = "无法打开音频解码器";
        return false;
    }

    // 格式识别与规格规划
    format_.sourceChannels = codec_ctx_->ch_layout.nb_channels > 0 ? codec_ctx_->ch_layout.nb_channels : codec_ctx_->channels;
    format_.sourceSampleRate = codec_ctx_->sample_rate;

    const int aiffBitDepth = nativeAudioAiffBitDepth(fmt_ctx_, codecpar);
    if (aiffBitDepth > 0) {
        format_.formatName = "AIFF";
        format_.sourceBitDepth = aiffBitDepth;
        format_.isDsd = false;
    } else if (codecpar->codec_id == AV_CODEC_ID_APE) {
        format_.formatName = "APE";
        format_.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        format_.isDsd = false;
    } else if (codecpar->codec_id == AV_CODEC_ID_ALAC) {
        format_.formatName = "ALAC";
        format_.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        format_.isDsd = false;
    } else if (codecpar->codec_id == AV_CODEC_ID_WAVPACK) {
        format_.formatName = "WavPack";
        format_.sourceBitDepth = codecpar->bits_per_raw_sample > 0 ? codecpar->bits_per_raw_sample : 16;
        format_.isDsd = false;
    } else if (codecpar->codec_id == AV_CODEC_ID_DSD_LSBF ||
               codecpar->codec_id == AV_CODEC_ID_DSD_MSBF ||
               codecpar->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
               codecpar->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR) {
        format_.isDsd = true;
        format_.sourceBitDepth = 1;
        int64_t dsdRate = codecpar->sample_rate;
        if (dsdRate < 1000000) dsdRate *= 8;
        int mult = static_cast<int>(dsdRate / 44100);
        if (mult >= 512) format_.formatName = "DSD512";
        else if (mult >= 256) format_.formatName = "DSD256";
        else if (mult >= 128) format_.formatName = "DSD128";
        else format_.formatName = "DSD64";
    } else {
        errorOut = "不受支持的解码格式";
        return false;
    }

    // 确定交付 Media3 的 PCM 规格
    if (format_.isDsd) {
        // DSD 转 PCM：默认 192 kHz，保留转换器生成的全部 32 位整数数据。
        format_.outputSampleRate = config_.targetDsdSampleRate > 0 ? config_.targetDsdSampleRate : 192000;
        format_.outputChannels = std::min(2, format_.sourceChannels > 0 ? format_.sourceChannels : 2);
        // Media3 C.ENCODING_PCM_32BIT = 22 (0x16, 4 bytes per sample)
        format_.outputEncoding = 22;
        format_.outputBytesPerFrame = format_.outputChannels * 4;
    } else {
        // 无损音频：保持采样率与声道
        format_.outputSampleRate = format_.sourceSampleRate;
        format_.outputChannels = format_.sourceChannels;
        if (format_.sourceBitDepth <= 16) {
            // Media3 C.ENCODING_PCM_16BIT = 2 (2 bytes per sample)
            format_.outputEncoding = 2;
            format_.outputBytesPerFrame = format_.outputChannels * 2;
        } else {
            // 24-bit / 32-bit：使用 32-bit 容器（C.ENCODING_PCM_32BIT = 22），有效位深保留 24 或 32
            format_.outputEncoding = 22;
            format_.outputBytesPerFrame = format_.outputChannels * 4;
        }
    }

    // 时长计算（微秒）
    if (fmt_ctx_->duration != AV_NOPTS_VALUE && fmt_ctx_->duration > 0) {
        format_.durationUs = fmt_ctx_->duration;
    } else if (audio_stream_->duration != AV_NOPTS_VALUE && audio_stream_->time_base.den > 0) {
        format_.durationUs = av_rescale_q(audio_stream_->duration, audio_stream_->time_base, {1, 1000000});
    }

    if (!initSwr()) {
        errorOut = "无法初始化音频转换器 (SwrContext)";
        return false;
    }

    return true;
}

bool NativeAudioSession::initSwr() {
    if (swr_ctx_) {
        swr_free(&swr_ctx_);
    }

    AVChannelLayout out_ch_layout;
    av_channel_layout_default(&out_ch_layout, format_.outputChannels);

    AVSampleFormat out_sample_fmt = (format_.outputEncoding == 2) ? AV_SAMPLE_FMT_S16 : AV_SAMPLE_FMT_S32;

    int ret = swr_alloc_set_opts2(
        &swr_ctx_,
        &out_ch_layout,
        out_sample_fmt,
        format_.outputSampleRate,
        &codec_ctx_->ch_layout,
        codec_ctx_->sample_fmt,
        codec_ctx_->sample_rate,
        0,
        nullptr);

    av_channel_layout_uninit(&out_ch_layout);

    if (ret < 0 || !swr_ctx_) {
        return false;
    }

    // 仅转换 DSD 时增强滤波；同规格整数无损音频继续保持原来的精度路径。
    if (format_.isDsd && !configureNativeAudioResampling(swr_ctx_)) return false;

    ret = swr_init(swr_ctx_);
    return ret >= 0;
}

int NativeAudioSession::convertFrameToStaging(AVFrame *frame) {
    if (!swr_ctx_ || !frame) return 0;

    int in_samples = frame->nb_samples;
    // 估算输出样本数
    int64_t max_out_samples = swr_get_out_samples(swr_ctx_, in_samples);
    if (max_out_samples <= 0) max_out_samples = in_samples;

    int out_bytes_needed = static_cast<int>(max_out_samples * format_.outputBytesPerFrame);
    size_t current_len = pcm_staging_.size() - staging_offset_;
    size_t new_size = current_len + out_bytes_needed;

    // 移动现存有效数据到开头
    if (staging_offset_ > 0) {
        if (current_len > 0) {
            std::memmove(pcm_staging_.data(), pcm_staging_.data() + staging_offset_, current_len);
        }
        pcm_staging_.resize(current_len);
        staging_offset_ = 0;
    }

    pcm_staging_.resize(new_size);
    uint8_t *out_ptr = pcm_staging_.data() + current_len;

    int converted_samples = swr_convert(
        swr_ctx_,
        &out_ptr,
        static_cast<int>(max_out_samples),
        const_cast<const uint8_t **>(frame->data),
        in_samples);

    if (converted_samples < 0) {
        pcm_staging_.resize(current_len);
        return 0;
    }

    int actual_bytes = converted_samples * format_.outputBytesPerFrame;
    pcm_staging_.resize(current_len + actual_bytes);
    return actual_bytes;
}

int NativeAudioSession::flushSwrToStaging() {
    if (!swr_ctx_ || swr_drained_) return 0;

    int64_t remaining_samples = swr_get_delay(swr_ctx_, format_.outputSampleRate);
    if (remaining_samples <= 0) {
        swr_drained_ = true;
        return 0;
    }

    int out_bytes_needed = static_cast<int>(remaining_samples * format_.outputBytesPerFrame);
    size_t current_len = pcm_staging_.size() - staging_offset_;
    size_t new_size = current_len + out_bytes_needed;

    if (staging_offset_ > 0) {
        if (current_len > 0) {
            std::memmove(pcm_staging_.data(), pcm_staging_.data() + staging_offset_, current_len);
        }
        pcm_staging_.resize(current_len);
        staging_offset_ = 0;
    }

    pcm_staging_.resize(new_size);
    uint8_t *out_ptr = pcm_staging_.data() + current_len;

    int converted_samples = swr_convert(
        swr_ctx_,
        &out_ptr,
        static_cast<int>(remaining_samples),
        nullptr,
        0);

    swr_drained_ = true;
    if (converted_samples <= 0) {
        pcm_staging_.resize(current_len);
        return 0;
    }

    pcm_staging_.resize(current_len + converted_samples * format_.outputBytesPerFrame);
    return converted_samples * format_.outputBytesPerFrame;
}

int NativeAudioSession::readPcm(uint8_t *directBuffer, int maxBytes, int64_t &outPtsUs) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (cancelled_.load() || !directBuffer || maxBytes <= 0) return 0;

    // 每次交付按整数帧对齐
    int frameBytes = format_.outputBytesPerFrame;
    int targetBytes = (maxBytes / frameBytes) * frameBytes;
    if (targetBytes <= 0) return 0;

    while (true) {
        size_t available_bytes = pcm_staging_.size() - staging_offset_;
        if (available_bytes >= static_cast<size_t>(targetBytes)) {
            // 缓存中有足够的数据，直接交付
            std::memcpy(directBuffer, pcm_staging_.data() + staging_offset_, targetBytes);
            staging_offset_ += targetBytes;

            outPtsUs = baseTimeUs_ + (outputFramesDelivered_ * 1000000LL) / format_.outputSampleRate;
            outputFramesDelivered_ += (targetBytes / frameBytes);
            return targetBytes;
        }

        if (output_eof_) {
            if (available_bytes > 0) {
                int deliver = (static_cast<int>(available_bytes) / frameBytes) * frameBytes;
                if (deliver > 0) {
                    std::memcpy(directBuffer, pcm_staging_.data() + staging_offset_, deliver);
                    staging_offset_ += deliver;

                    outPtsUs = baseTimeUs_ + (outputFramesDelivered_ * 1000000LL) / format_.outputSampleRate;
                    outputFramesDelivered_ += (deliver / frameBytes);
                    return deliver;
                }
            }
            return -1; // 真正到达流末尾
        }

        // 解码新帧
        int ret = avcodec_receive_frame(codec_ctx_, decoded_frame_);
        if (ret == 0) {
            convertFrameToStaging(decoded_frame_);
            av_frame_unref(decoded_frame_);
            continue;
        }

        if (ret == AVERROR_EOF) {
            // 解码器已排空，排空重采样器
            decoder_drained_ = true;
            flushSwrToStaging();
            output_eof_ = true;
            continue;
        }

        if (ret == AVERROR(EAGAIN)) {
            if (input_eof_) {
                // 已经读取完文件，向解码器发送 null packet 以进入 drain 状态
                avcodec_send_packet(codec_ctx_, nullptr);
                continue;
            }

            // 读取新的 packet
            ret = av_read_frame(fmt_ctx_, packet_);
            if (ret < 0) {
                if (ret == AVERROR_EXIT || cancelled_.load()) {
                    // 主动取消（例如 Seek 或切歌），干净退出让出线程，绝对不污染流结束状态
                    return 0;
                } else if (ret == AVERROR_EOF) {
                    input_eof_ = true;
                    avcodec_send_packet(codec_ctx_, nullptr);
                } else {
                    LOGE("av_read_frame 错误: %d", ret);
                    return 0; // 发生临时 I/O 错误不谎报 EOF 引起误切歌
                }
            } else {
                if (packet_->stream_index == audio_stream_idx_) {
                    if (just_seeked_ && packet_->pts != AV_NOPTS_VALUE) {
                        baseTimeUs_ = av_rescale_q(packet_->pts, audio_stream_->time_base, {1, 1000000});
                        outputFramesDelivered_ = 0;
                        just_seeked_ = false;
                    }
                    avcodec_send_packet(codec_ctx_, packet_);
                }
                av_packet_unref(packet_);
            }
            continue;
        }

        // 其他解码错误
        LOGE("avcodec_receive_frame 错误: %d", ret);
        return -1;
    }
}

bool NativeAudioSession::seekTo(int64_t timeUs) {
    cancelled_.store(false);
    if (ioBridge_) {
        ioBridge_->resetCancelled();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!fmt_ctx_ || audio_stream_idx_ < 0) return false;
    if (avio_) {
        avio_->error = 0;
        avio_->eof_reached = 0;
    }

    pcm_staging_.clear();
    staging_offset_ = 0;
    input_eof_ = false;
    output_eof_ = false;
    decoder_drained_ = false;
    swr_drained_ = false;

    if (format_.isDsd) {
        // FFmpeg 6.1 的 DSD 解码器未实现 flush，必须重建以清除 FIR 历史。
        const AVCodec *codec = avcodec_find_decoder(audio_stream_->codecpar->codec_id);
        AVCodecContext *replacement = avcodec_alloc_context3(codec);
        if (!replacement) return false;
        replacement->thread_count = 1;
        if (avcodec_parameters_to_context(replacement, audio_stream_->codecpar) < 0 ||
            avcodec_open2(replacement, codec, nullptr) < 0) {
            avcodec_free_context(&replacement);
            return false;
        }
        avcodec_free_context(&codec_ctx_);
        codec_ctx_ = replacement;
    } else if (codec_ctx_) {
        avcodec_flush_buffers(codec_ctx_);
    }
    if (!initSwr()) return false;

    int64_t targetPts = av_rescale_q(timeUs, {1, 1000000}, audio_stream_->time_base);
    int ret = avformat_seek_file(fmt_ctx_, audio_stream_idx_, INT64_MIN, targetPts, targetPts, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        ret = av_seek_frame(fmt_ctx_, audio_stream_idx_, targetPts, AVSEEK_FLAG_BACKWARD);
    }
    if (ret < 0) {
        ret = av_seek_frame(fmt_ctx_, -1, timeUs, AVSEEK_FLAG_BACKWARD);
    }

    baseTimeUs_ = timeUs;
    outputFramesDelivered_ = 0;
    just_seeked_ = true;
    return ret >= 0;
}
