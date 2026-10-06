/**
 * @file usb-audio-output.cpp
 * @brief Direct USB audio output via Linux usbdevfs isochronous transfers.
 *
 * Pipeline: pre-allocated ring buffer of USB_AUDIO_NUM_URBS (= 80) URBs.
 * No malloc/free during streaming — completely avoids ARM MTE pointer tag
 * issues on Samsung devices.
 *
 * 传输提交、回收和尾部处理由 usb-audio-transfers.cpp 统一管理。
 */

#include "usb-audio-output.h"
#include "usb-pcm-packet-plan.h"

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#define TAG "UsbAudioOutput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Float → PCM conversion ──────────────────────────────────────────

static inline float clampf(float v) { return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v); }

// Bit-perfect float→int conversion matching FFmpeg's libswresample normalization.
// FFmpeg normalizes: int / 2^N (e.g., int16 / 32768.0).
// Reconversion: float × 2^N gives exact round-trip for 16-bit and 24-bit because:
//   - 2^N is exactly representable in float32 (power of 2)
//   - float32 has 24-bit mantissa, covering int16 (16-bit) and int24 (24-bit) exactly
// Clamp after scaling to handle the asymmetry: -1.0 × 32768 = -32768 (valid min),
// but +1.0 × 32768 = 32768 (exceeds max 32767, needs clamping).

static void convertFloatToInt16(const float *src, uint8_t *dst, int n) {
    auto *out = reinterpret_cast<int16_t *>(dst);
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i]) * 32768.0f;
        if (s > 32767.0f) s = 32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        out[i] = (int16_t)s;
    }
}
static void convertFloatToInt24(const float *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i]) * 8388608.0f;
        if (s > 8388607.0f) s = 8388607.0f;
        if (s < -8388608.0f) s = -8388608.0f;
        int32_t v = (int32_t)s;
        dst[i*3] = v & 0xFF; dst[i*3+1] = (v>>8) & 0xFF; dst[i*3+2] = (v>>16) & 0xFF;
    }
}
static void convertFloatToInt32(const float *src, uint8_t *dst, int n) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < n; i++) {
        // Use double: float32 can't represent 2147483648.0 exactly (needs 31 bits,
        // float32 has 24-bit mantissa). Double has 53-bit mantissa — sufficient.
        double s = (double)clampf(src[i]) * 2147483648.0;
        if (s > 2147483647.0) s = 2147483647.0;
        if (s < -2147483648.0) s = -2147483648.0;
        out[i] = (int32_t)s;
    }
}

static void convertFloatToPcmSubslot(
        const float *src, uint8_t *dst, int n, int validBits, int subslotBytes);

// ── Ring buffer management ──────────────────────────────────────────

/**
 * Allocate all URB slots in the ring buffer.
 * Called once at stream creation. All memory stays alive until destroy.
 */
static bool allocRing(UsbAudioContext *ctx) {
    size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                           USB_AUDIO_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        ctx->ring[i].urb = (struct usbdevfs_urb *)calloc(1, urbStructSize);
        ctx->ring[i].buffer = (uint8_t *)malloc(USB_AUDIO_URB_BUFFER_SIZE);
        ctx->ring[i].dataLength = 0;
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: OOM at slot %d", i);
            // Free what we allocated
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
            }
            return false;
        }
    }
    ctx->ringAllocated = true;
    return true;
}

/**
 * Free all URB slots. Called once at stream destruction.
 */
static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb = nullptr;
        ctx->ring[i].buffer = nullptr;
    }
    ctx->ringAllocated = false;
}

static bool allocFeedbackUrb(UsbAudioContext *ctx) {
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = (struct usbdevfs_urb *)calloc(1, sz);
    if (!ctx->feedbackUrb) return false;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));
    return true;
}

// ── JNI entry points ────────────────────────────────────────────────

// Forward declaration (defined after integer padding functions, non-static for native-audio-engine)
void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint ifId, jint epOut, jint epFb,
        jint rate, jint ch, jint bits, jint subslotBytes, jint maxPkt) {
    LOGI("Create: fd=%d ep=0x%02x rate=%d ch=%d bits=%d subslot=%d maxPkt=%d",
         fd, epOut, rate, ch, bits, subslotBytes, maxPkt);
    auto *ctx = new(std::nothrow) UsbAudioContext();
    if (!ctx) return 0;
    ctx->fd = fd;
    ctx->interfaceId = ifId;
    ctx->endpointOut = epOut;
    ctx->endpointFeedback = epFb;
    ctx->sampleRate = rate;
    ctx->channelCount = ch;
    ctx->bitDepth = bits;
    ctx->bytesPerSample = subslotBytes;
    ctx->bytesPerFrame = subslotBytes * ch;
    ctx->maxPacketSize = maxPkt;
    ctx->running.store(false);
    ctx->transferBuffer = nullptr;
    ctx->transferBufferCapacity = 0;
    ctx->mergedBuffer = nullptr;
    ctx->mergedBufferCapacity = 0;
    ctx->framesWritten = 0;
    ctx->interfaceClaimed = true;

    ctx->transfers.reset();
    ctx->completedFrames.store(0);
    ctx->failed = false;
    ctx->ringAllocated = false;
    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf = rate / 8000.0;
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    memset(ctx->ring, 0, sizeof(ctx->ring));
    ctx->feedbackUrb = nullptr;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    if (!allocRing(ctx)) {
        delete ctx;
        return 0;
    }

    if (!allocFeedbackUrb(ctx)) {
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetAltSetting(
        JNIEnv *, jobject, jlong h, jint alt) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    struct usbdevfs_setinterface si = {};
    si.interface = (unsigned)ctx->interfaceId;
    si.altsetting = (unsigned)alt;
    int r = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
    LOGI("setAlt(%d,%d): ret=%d errno=%d", ctx->interfaceId, alt, r, errno);
    return r >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetSampleRate(
        JNIEnv *, jobject, jlong h, jint rate, jint csId) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    ctx->sampleRate = rate;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    ctx->running.store(true);
    ctx->framesWritten = 0;

    ctx->transfers.reset();
    ctx->completedFrames.store(0);
    ctx->failed = false;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;

    ctx->calibratedFpmf = ctx->sampleRate / 8000.0;
    // 复用持久反馈请求，完成之前不释放其缓冲。
    submitFeedbackUrb(ctx);
    // 保留起播前的反馈校准窗口，避免首批音频全按名义时钟排包。
    if (ctx->endpointFeedback > 0) usleep(2000);
    return pollUsbTransfers(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(
        JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint totalSamples = env->GetArrayLength(pcm);
    if (totalSamples <= 0) return;
    int totalFrames = totalSamples / ctx->channelCount;
    int totalBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < totalBytes) {
        uint8_t *resized = (uint8_t *)realloc(ctx->transferBuffer, totalBytes);
        if (!resized) return;
        ctx->transferBuffer = resized;
        ctx->transferBufferCapacity = totalBytes;
    }

    struct timespec writeStart, writeEnd;
    clock_gettime(CLOCK_MONOTONIC, &writeStart);
    static long writeCallCount = 0;
    writeCallCount++;

    // Convert float PCM to target bit depth
    jfloat *f = env->GetFloatArrayElements(pcm, nullptr);
    if (!f) return;
    if (ctx->bitDepth == 16 && ctx->bytesPerSample == 2) {
        convertFloatToInt16(f, ctx->transferBuffer, totalSamples);
    } else if (ctx->bitDepth == 24 && ctx->bytesPerSample == 3) {
        convertFloatToInt24(f, ctx->transferBuffer, totalSamples);
    } else if (ctx->bitDepth == 32 && ctx->bytesPerSample == 4) {
        convertFloatToInt32(f, ctx->transferBuffer, totalSamples);
    } else if (ctx->bitDepth > 0 && ctx->bitDepth <= ctx->bytesPerSample * 8) {
        convertFloatToPcmSubslot(
                f, ctx->transferBuffer, totalSamples, ctx->bitDepth, ctx->bytesPerSample);
    } else {
        env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);
        return;
    }
    env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);

    // Submit converted PCM to USB pipeline (shared with raw path)
    submitPcmToUrbs(ctx, ctx->transferBuffer, totalBytes);

    if (ctx->running.load() && !ctx->failed) ctx->framesWritten += totalFrames;

    clock_gettime(CLOCK_MONOTONIC, &writeEnd);
    long writeUs = (writeEnd.tv_sec - writeStart.tv_sec) * 1000000L +
                   (writeEnd.tv_nsec - writeStart.tv_nsec) / 1000L;
    // Log every call that took > 10ms, or every 100th call
    if (writeUs > 10000 || writeCallCount % 100 == 0) {
        LOGI("nativeWrite #%ld: %d samples, %ldus (%.1fms), inflight=%d",
             writeCallCount, totalSamples, writeUs, writeUs / 1000.0, ctx->transfers.pending);
    }

    // Periodic logging (~once per second)
    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("Write: %lld frames (~%.0f sec) inflight=%d fpmf=%.4f (%.1f Hz)",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->transfers.pending, ctx->calibratedFpmf, ctx->calibratedFpmf * 8000.0);
    }
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);

}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !cancelUsbTransfers(ctx)) return JNI_FALSE;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->framesWritten = 0;
    ctx->transfers.reset();
    ctx->completedFrames.store(0);
    ctx->failed = false;
    ctx->running.store(true);
    submitFeedbackUrb(ctx);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePollPending(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load() || !pollUsbTransfers(ctx)) return -1;
    return ctx->residualBytes > 0 || ctx->transfers.pending > 0 ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeFinish(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && finishUsbTransfers(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    const int pending = ctx->transfers.pending;
    return cancelUsbTransfers(ctx) ? pending : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_TRUE;
    // 回收失败时保留缓冲，避免内核仍使用它们时释放内存。
    if (!cancelUsbTransfers(ctx)) return JNI_FALSE;
    freeRing(ctx);
    free(ctx->feedbackUrb);
    free(ctx->transferBuffer);
    free(ctx->mergedBuffer);
    delete ctx;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeIsRunning(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    return ctx->running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetCompletedFrames(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    return (jlong)ctx->completedFrames.load();
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbReset(
        JNIEnv *, jclass, jint fd) {
    LOGI("USBDEVFS_RESET fd=%d", fd);
    int ret = ioctl(fd, USBDEVFS_RESET, 0);
    if (ret < 0) { LOGE("RESET FAILED errno=%d", errno); return ret; }
    LOGI("RESET OK, claiming interfaces...");
    struct usbdevfs_ioctl cmd = {}; cmd.ifno = 0; cmd.ioctl_code = USBDEVFS_DISCONNECT;
    ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i0 = 0; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i0);
    cmd.ifno = 1; ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i1 = 1; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i1);
    struct usbdevfs_setinterface si = {}; si.interface = 1; si.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    return 0;
}

} // extern "C" — pause for non-JNI functions used by native-audio-engine

// ── Integer padding (lossless, zero float) ──────────────────────────

// 16-bit → 32-bit: shift left 16
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = (int32_t)in16[i] << 16;
}

static void convertFloatToPcmSubslot(
        const float *src, uint8_t *dst, int n, int validBits, int subslotBytes) {
    const int containerBits = subslotBytes * 8;
    const int shift = containerBits - validBits;
    const double scale = std::ldexp(1.0, validBits - 1);
    const double maximum = scale - 1.0;
    const double minimum = -scale;
    for (int i = 0; i < n; i++) {
        double sample = static_cast<double>(clampf(src[i])) * scale;
        if (sample > maximum) sample = maximum;
        if (sample < minimum) sample = minimum;
        const int64_t aligned = static_cast<int64_t>(sample) * (1LL << shift);
        for (int byte = 0; byte < subslotBytes; byte++) {
            dst[i * subslotBytes + byte] =
                    static_cast<uint8_t>((aligned >> (byte * 8)) & 0xFF);
        }
    }
}

// 16-bit → 24-bit packed：保留原有效位并在低位补零
void padInt16ToInt24(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < numSamples; i++) {
        int32_t sample = (int32_t)in16[i] << 8;
        dst[i * 3] = (uint8_t)(sample & 0xFF);
        dst[i * 3 + 1] = (uint8_t)((sample >> 8) & 0xFF);
        dst[i * 3 + 2] = (uint8_t)((sample >> 16) & 0xFF);
    }
}

// int32 (24-bit sign-extended from libFLAC) → 32-bit: shift left 8
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in32 = reinterpret_cast<const int32_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = in32[i] << 8;
}

// 24-bit packed (3 bytes/sample) → 32-bit: read 3 bytes, sign-extend, shift left 8
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < numSamples; i++) {
        int32_t s = src[i*3] | (src[i*3+1] << 8) | (src[i*3+2] << 16);
        if (s & 0x800000) s |= 0xFF000000;  // sign-extend from 24 to 32 bits
        out[i] = s << 8;  // shift to fill 32-bit range
    }
}

// ── Shared URB submission logic ─────────────────────────────────────

/**
 * Submit PCM data (already in the target bit depth) to the USB pipeline.
 * Used by both nativeUsbAudioWrite (float path) and nativeUsbAudioWriteRaw.
 */
// ── Raw bytes write (no float, for libFLAC integer path) ────────────

extern "C" {  // resume JNI functions

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(
        JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint inputBitDepth,
        jint validBitDepth) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint inputBytes = env->GetArrayLength(pcm);
    if (inputBytes <= 0) return;

    int inputBps = inputBitDepth / 8;
    int totalSamples = inputBytes / inputBps;
    int totalFrames = totalSamples / ctx->channelCount;
    int outputBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < outputBytes) {
        uint8_t *resized = (uint8_t *)realloc(ctx->transferBuffer, outputBytes);
        if (!resized) return;
        ctx->transferBuffer = resized;
        ctx->transferBufferCapacity = outputBytes;
    }

    jbyte *rawData = env->GetByteArrayElements(pcm, nullptr);
    if (!rawData) return;

    const int targetContainerBits = ctx->bytesPerSample * 8;
    if (inputBitDepth == targetContainerBits && validBitDepth == ctx->bitDepth &&
            validBitDepth == inputBitDepth) {
        memcpy(ctx->transferBuffer, rawData, inputBytes);
    } else {
        const auto *input = reinterpret_cast<const uint8_t *>(rawData);
        const int targetShift = targetContainerBits - validBitDepth;
        if (validBitDepth > ctx->bitDepth || targetShift < 0 || inputBitDepth < validBitDepth) {
            LOGE("WriteRaw: unsupported PCM layout container=%d valid=%d target=%d/%d",
                 inputBitDepth, validBitDepth, ctx->bitDepth, targetContainerBits);
            env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);
            return;
        }
        for (int i = 0; i < totalSamples; i++) {
            const uint8_t *sampleBytes = input + i * inputBps;
            int64_t sample = 0;
            for (int byte = 0; byte < inputBps; byte++) {
                sample |= static_cast<int64_t>(sampleBytes[byte]) << (byte * 8);
            }
            const int64_t signBit = 1LL << (inputBitDepth - 1);
            if ((sample & signBit) != 0) sample -= 1LL << inputBitDepth;
            const int64_t aligned = sample * (1LL << targetShift);
            uint8_t *output = ctx->transferBuffer + i * ctx->bytesPerSample;
            for (int byte = 0; byte < ctx->bytesPerSample; byte++) {
                output[byte] = static_cast<uint8_t>((aligned >> (byte * 8)) & 0xFF);
            }
        }
    }

    env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);

    // Submit to USB pipeline
    submitPcmToUrbs(ctx, ctx->transferBuffer, outputBytes);

    if (ctx->running.load() && !ctx->failed) ctx->framesWritten += totalFrames;
    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("WriteRaw: %lld frames (~%.0f sec) inflight=%d inputBits=%d fpmf=%.4f ",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->transfers.pending, validBitDepth, ctx->calibratedFpmf);
    }
}

} // extern "C"
