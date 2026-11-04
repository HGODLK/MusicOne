#include <jni.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include "lg_pcm_buffer_queue.h"
#include <cmath>
#include <memory>
#include <mutex>

namespace {
struct LgPcmOutput {
    SLObjectItf engineObject = nullptr, mix = nullptr, player = nullptr;
    SLEngineItf engine = nullptr;
    SLPlayItf play = nullptr;
    SLVolumeItf volume = nullptr;
    SLAndroidSimpleBufferQueueItf queue = nullptr;
    SLAndroidConfigurationItf config = nullptr;
    bool proxyAcquired = false;
    int frameBytes = 0;
    bool playing = false, draining = false;
    int64_t emptyEvents = 0;
    std::mutex mutex;
    std::unique_ptr<musicone::LgPcmBufferQueue> buffers;

    bool submit(const uint8_t *data, size_t bytes) {
        return (*queue)->Enqueue(queue, data, bytes) == SL_RESULT_SUCCESS;
    }

    static void consumed(SLAndroidSimpleBufferQueueItf, void *context) {
        auto *self = static_cast<LgPcmOutput *>(context);
        std::lock_guard<std::mutex> lock(self->mutex);
        if (self->buffers->consumed() && self->buffers->hardwareEmpty() && self->playing && !self->draining) {
            // 只记录活动播放时队列耗尽；日志在提交线程读取，回调不写日志。
            ++self->emptyEvents;
        }
    }

    ~LgPcmOutput() {
        // 销毁时不持有回调所需的锁；Destroy 返回后才释放队列内存。
        if (play) (*play)->SetPlayState(play, SL_PLAYSTATE_STOPPED);
        if (config && proxyAcquired) (*config)->ReleaseJavaProxy(config, SL_ANDROID_JAVA_PROXY_ROUTING);
        if (player) (*player)->Destroy(player);
        if (mix) (*mix)->Destroy(mix);
        if (engineObject) (*engineObject)->Destroy(engineObject);
    }

    bool open(int rate, int channels, int depth) {
        if (rate < 8000 || rate > 384000 || channels < 1 || channels > 2 ||
            (depth != 24 && depth != 32)) return false;
        frameBytes = channels * depth / 8;
        buffers = std::make_unique<musicone::LgPcmBufferQueue>(rate, frameBytes);
#define LG_CHECK(call) do { SLresult result = (call); if (result != SL_RESULT_SUCCESS) { \
    __android_log_print(ANDROID_LOG_WARN, "MusicOneLgAudio", "%s: %u", #call, result); return false; } } while (0)
        LG_CHECK(slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr));
        LG_CHECK((*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE));
        LG_CHECK((*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engine));
        LG_CHECK((*engine)->CreateOutputMix(engine, &mix, 0, nullptr, nullptr));
        LG_CHECK((*mix)->Realize(mix, SL_BOOLEAN_FALSE));
        SLDataLocator_AndroidSimpleBufferQueue location {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
            static_cast<SLuint32>(musicone::LgPcmBufferQueue::kSlots)};
        SLAndroidDataFormat_PCM_EX format {SL_ANDROID_DATAFORMAT_PCM_EX,
            static_cast<SLuint32>(channels), static_cast<SLuint32>(rate * 1000),
            static_cast<SLuint32>(depth), static_cast<SLuint32>(depth),
            channels == 1 ? SL_SPEAKER_FRONT_CENTER : SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
            SL_BYTEORDER_LITTLEENDIAN, SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT};
        SLDataSource source {&location, &format};
        SLDataLocator_OutputMix sinkLocation {SL_DATALOCATOR_OUTPUTMIX, mix};
        SLDataSink sink {&sinkLocation, nullptr};
        const SLInterfaceID ids[] {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION, SL_IID_VOLUME};
        const SLboolean required[] {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
        LG_CHECK((*engine)->CreateAudioPlayer(engine, &player, &source, &sink, 3, ids, required));
        LG_CHECK((*player)->GetInterface(player, SL_IID_ANDROIDCONFIGURATION, &config));
        SLint32 stream = SL_ANDROID_STREAM_MEDIA;
        LG_CHECK((*config)->SetConfiguration(config, SL_ANDROID_KEY_STREAM_TYPE, &stream, sizeof(stream)));
        // 避免 FAST／DEEP_BUFFER 偏好；以原采样率和高位整数格式交给 LG 策略选路。
        SLuint32 performance = SL_ANDROID_PERFORMANCE_NONE;
        LG_CHECK((*config)->SetConfiguration(config, SL_ANDROID_KEY_PERFORMANCE_MODE, &performance, sizeof(performance)));
        LG_CHECK((*player)->Realize(player, SL_BOOLEAN_FALSE));
        LG_CHECK((*player)->GetInterface(player, SL_IID_PLAY, &play));
        LG_CHECK((*player)->GetInterface(player, SL_IID_VOLUME, &volume));
        LG_CHECK((*player)->GetInterface(player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queue));
        LG_CHECK((*queue)->RegisterCallback(queue, consumed, this));
        return true;
#undef LG_CHECK
    }
};
LgPcmOutput *output(jlong handle) { return reinterpret_cast<LgPcmOutput *>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeOpen(JNIEnv *, jobject, jint rate, jint channels, jint depth) {
    auto *self = new LgPcmOutput();
    if (self->open(rate, channels, depth)) return reinterpret_cast<jlong>(self);
    delete self;
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeRouting(JNIEnv *, jobject, jlong handle) {
    auto *self = output(handle);
    jobject proxy = nullptr;
    if (!self || (*self->config)->AcquireJavaProxy(self->config, SL_ANDROID_JAVA_PROXY_ROUTING, &proxy)
        != SL_RESULT_SUCCESS) return nullptr;
    self->proxyAcquired = true;
    return proxy;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeEnqueue(JNIEnv *env, jobject, jlong handle, jobject buffer, jint bytes) {
    auto *self = output(handle);
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!self || !data || bytes <= 0 || bytes % self->frameBytes != 0 ||
        env->GetDirectBufferCapacity(buffer) < bytes) return -1;
    std::lock_guard<std::mutex> lock(self->mutex);
    self->draining = false;
    return self->buffers->write(data, bytes, [self](const uint8_t *pcm, size_t size) { return self->submit(pcm, size); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeDrain(JNIEnv *, jobject, jlong handle) {
    auto *self = output(handle);
    if (!self) return false;
    std::lock_guard<std::mutex> lock(self->mutex);
    self->draining = true;
    // 曲末或格式切换时，未满 20ms 的真实尾部也必须提交，不能等下一首补齐。
    return self->buffers->flushPartial([self](const uint8_t *pcm, size_t size) { return self->submit(pcm, size); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativePlay(JNIEnv *, jobject, jlong handle, jboolean playing) {
    auto *self = output(handle);
    if (!self) return false;
    // 状态调用不持有回调锁，避免驱动等待回调时发生互等。
    const auto result = (*self->play)->SetPlayState(self->play, playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
    std::lock_guard<std::mutex> lock(self->mutex);
    if (result == SL_RESULT_SUCCESS) self->playing = playing;
    return result == SL_RESULT_SUCCESS;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeVolume(JNIEnv *, jobject, jlong handle, jfloat gain) {
    auto *self = output(handle);
    if (!self || !std::isfinite(gain)) return false;
    const auto level = gain <= 0 ? SL_MILLIBEL_MIN : static_cast<SLmillibel>(2000 * std::log10(std::fmin(gain, 1.f)));
    return (*self->volume)->SetVolumeLevel(self->volume, level) == SL_RESULT_SUCCESS;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeCompletedFrames(JNIEnv *, jobject, jlong handle) {
    auto *self = output(handle);
    if (!self) return 0;
    std::lock_guard<std::mutex> lock(self->mutex);
    return self->buffers->completedFrames();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeHasPendingData(JNIEnv *, jobject, jlong handle) {
    auto *self = output(handle);
    if (!self) return false;
    std::lock_guard<std::mutex> lock(self->mutex);
    return self->buffers->hasPendingData();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeEmptyEvents(JNIEnv *, jobject, jlong handle) {
    auto *self = output(handle);
    if (!self) return 0;
    std::lock_guard<std::mutex> lock(self->mutex);
    return self->emptyEvents;
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_NativeLgPcmOutput_nativeClose(JNIEnv *, jobject, jlong handle) { delete output(handle); }
