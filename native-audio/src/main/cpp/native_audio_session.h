#ifndef NATIVE_AUDIO_SESSION_H
#define NATIVE_AUDIO_SESSION_H

#include "native_audio_io.h"
#include <string>
#include <vector>
#include <cstdint>
#include <mutex>
#include <atomic>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

struct SessionConfig {
    int targetDsdSampleRate{192000}; // 所有 DSD 容器与倍率共用 192 kHz、S32 输出。
};

struct SessionAudioFormat {
    std::string formatName;      // APE, ALAC, WavPack, DSD64 等
    int sourceBitDepth{16};      // 有效位深（如 16, 24, 32, DSD 为 1）
    int sourceSampleRate{0};     // 源采样率
    int sourceChannels{0};       // 源声道数
    bool isDsd{false};

    int outputSampleRate{0};     // 交付给 Media3 的采样率
    int outputChannels{0};       // 交付给 Media3 的声道数
    int outputEncoding{2};       // Media3 C.ENCODING_PCM_16BIT=2, 32BIT=22(0x16), 24BIT=21(0x15)
    int outputBytesPerFrame{4};  // 单帧字节数（channels * bytes_per_sample）
    int64_t durationUs{0};       // 总时长（微秒）
};

class NativeAudioSession {
public:
    NativeAudioSession(NativeAudioIoBridge *ioBridge, const SessionConfig &config = SessionConfig());
    ~NativeAudioSession();

    bool open(std::string &errorOut);
    int readPcm(uint8_t *directBuffer, int maxBytes, int64_t &outPtsUs);
    bool seekTo(int64_t timeUs);
    void cancel();
    bool isCancelled() const { return cancelled_.load(); }
    void close();

    const SessionAudioFormat &getFormat() const { return format_; }

private:
    NativeAudioIoBridge *ioBridge_{nullptr};
    SessionConfig config_;
    SessionAudioFormat format_;

    AVIOContext *avio_{nullptr};
    AVFormatContext *fmt_ctx_{nullptr};
    AVCodecContext *codec_ctx_{nullptr};
    SwrContext *swr_ctx_{nullptr};
    AVStream *audio_stream_{nullptr};
    int audio_stream_idx_{-1};

    AVPacket *packet_{nullptr};
    AVFrame *decoded_frame_{nullptr};

    std::atomic<bool> cancelled_{false};
    std::mutex mutex_;

    bool input_eof_{false};
    bool output_eof_{false};
    bool decoder_drained_{false};
    bool swr_drained_{false};
    bool just_seeked_{false};

    int64_t baseTimeUs_{0};
    int64_t outputFramesDelivered_{0};

    // 内部暂存未完全输出的 PCM 数据
    std::vector<uint8_t> pcm_staging_;
    size_t staging_offset_{0};

    bool initSwr();
    int convertFrameToStaging(AVFrame *frame);
    int flushSwrToStaging();
};

#endif // NATIVE_AUDIO_SESSION_H
