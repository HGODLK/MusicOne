#include <jni.h>
#include <cstdint>
#include <vector>
#include <cmath>
#include <algorithm>
#include "native_audio_resample_quality.h"

struct PcmResampler {
    SwrContext *context = nullptr;
    int channels = 0;
    int inputBytes = 0;
    bool packed24 = false;
    std::vector<uint8_t> input;
    std::vector<int32_t> unpacked;
    std::vector<double> filtered;
    std::vector<int32_t> output;
    ~PcmResampler() { swr_free(&context); }
};

static jbyteArray failPcmConversion(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_NativePcmResampler_nativeCreate(
        JNIEnv *, jobject, jint inputRate, jint outputRate, jint channels, jint encoding) {
    AVSampleFormat inputFormat;
    int bytes;
    switch (encoding) {
        case 2: inputFormat = AV_SAMPLE_FMT_S16; bytes = 2; break;
        case 4: inputFormat = AV_SAMPLE_FMT_FLT; bytes = 4; break;
        case 21: inputFormat = AV_SAMPLE_FMT_S32; bytes = 3; break;
        case 22: inputFormat = AV_SAMPLE_FMT_S32; bytes = 4; break;
        default: return 0;
    }
    if (inputRate <= 0 || outputRate <= 0 || channels < 1 || channels > 2) return 0;
    auto *resampler = new PcmResampler();
    resampler->channels = channels;
    resampler->inputBytes = bytes;
    resampler->packed24 = bytes == 3;
    AVChannelLayout layout;
    av_channel_layout_default(&layout, channels);
    const int result = swr_alloc_set_opts2(&resampler->context, &layout, AV_SAMPLE_FMT_DBL,
        outputRate, &layout, inputFormat, inputRate, 0, nullptr);
    av_channel_layout_uninit(&layout);
    if (result < 0 || !configureNativeAudioResampling(resampler->context) ||
            swr_init(resampler->context) < 0) {
        delete resampler;
        return 0;
    }
    return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_musicone_nativeaudio_NativePcmResampler_nativeConvert(
        JNIEnv *env, jobject, jlong handle, jbyteArray bytes, jdouble requestedGain) {
    auto *resampler = reinterpret_cast<PcmResampler *>(handle);
    if (!resampler) return failPcmConversion(env, "PCM 转换器已释放");
    const int length = bytes ? env->GetArrayLength(bytes) : 0;
    const int frameBytes = resampler->channels * resampler->inputBytes;
    if (length % frameBytes != 0) return failPcmConversion(env, "PCM 输入未按音频帧对齐");
    const int frames = length / frameBytes;
    const uint8_t *input = nullptr;
    if (bytes) {
        if (length == 0) return env->NewByteArray(0);
        resampler->input.resize(length);
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(resampler->input.data()));
        if (env->ExceptionCheck()) return nullptr;
        input = resampler->input.data();
        if (resampler->packed24) {
            resampler->unpacked.resize(length / 3);
            // 三字节有符号 PCM 左对齐到 S32，保留全部 24 个有效位。
            for (int i = 0; i < length / 3; ++i) {
                const uint32_t sample = uint32_t(input[i * 3]) |
                    (uint32_t(input[i * 3 + 1]) << 8) | (uint32_t(input[i * 3 + 2]) << 16);
                resampler->unpacked[i] = static_cast<int32_t>(sample << 8);
            }
            input = reinterpret_cast<const uint8_t *>(resampler->unpacked.data());
        }
    }
    const int capacity = swr_get_out_samples(resampler->context, frames);
    if (capacity < 0) return failPcmConversion(env, "PCM 输出容量计算失败");
    resampler->filtered.resize(static_cast<size_t>(capacity) * resampler->channels);
    auto *output = reinterpret_cast<uint8_t *>(resampler->filtered.data());
    const int converted = swr_convert(resampler->context, &output, capacity,
        bytes ? &input : nullptr, frames);
    if (converted < 0) return failPcmConversion(env, "PCM 兼容转换失败");
    const int samples = converted * resampler->channels;
    const double gain = std::isfinite(requestedGain) ? std::max(0.0, std::min(1.0, requestedGain)) : 0.0;
    resampler->output.resize(samples);
    // 滤波后先合并音量，再只量化一次 S32，避免降音量前就截断滤波的满幅峰值。
    for (int i = 0; i < samples; ++i) {
        double value = resampler->filtered[i] * gain * 2147483648.0;
        if (!std::isfinite(value)) value = 0;
        value = std::max(-2147483648.0, std::min(2147483647.0, value));
        resampler->output[i] = static_cast<int32_t>(std::llround(value));
    }
    const int outputBytes = converted * resampler->channels * 4;
    auto result = env->NewByteArray(outputBytes);
    if (result && outputBytes > 0) env->SetByteArrayRegion(result, 0, outputBytes,
        reinterpret_cast<const jbyte *>(resampler->output.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativePcmResampler_nativeReset(JNIEnv *, jobject, jlong handle) {
    auto *resampler = reinterpret_cast<PcmResampler *>(handle);
    if (!resampler) return JNI_FALSE;
    swr_close(resampler->context);
    return swr_init(resampler->context) >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_NativePcmResampler_nativeRelease(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<PcmResampler *>(handle);
}
