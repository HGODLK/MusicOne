#include <jni.h>
#include <string>
#include <android/log.h>
#include "native_audio_io.h"
#include "native_audio_probe.h"
#include "native_audio_session.h"

#define LOG_TAG "NativeAudioJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeProbe(
        JNIEnv *env, jobject thiz, jobject ioBridgeObj) {
    if (!ioBridgeObj) return nullptr;

    auto *ioBridge = new NativeAudioIoBridge(g_jvm, ioBridgeObj);
    ProbeResult res = NativeAudioProbe::probe(ioBridge);
    delete ioBridge;

    jclass cls = env->FindClass("com/musicone/nativeaudio/ProbeResultRaw");
    if (!cls) return nullptr;

    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;JIIIZZIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[BLjava/lang/String;)V");
    if (!ctor) return nullptr;

    jstring errStr = res.errorMessage.empty() ? nullptr : env->NewStringUTF(res.errorMessage.c_str());
    jstring fmtStr = res.formatName.empty() ? nullptr : env->NewStringUTF(res.formatName.c_str());
    jstring codecStr = res.codecName.empty() ? nullptr : env->NewStringUTF(res.codecName.c_str());
    jstring titleStr = res.title.empty() ? nullptr : env->NewStringUTF(res.title.c_str());
    jstring artistStr = res.artist.empty() ? nullptr : env->NewStringUTF(res.artist.c_str());
    jstring albumStr = res.album.empty() ? nullptr : env->NewStringUTF(res.album.c_str());
    jstring coverMimeStr = res.coverMime.empty() ? nullptr : env->NewStringUTF(res.coverMime.c_str());

    jbyteArray coverBytes = nullptr;
    if (!res.coverData.empty()) {
        coverBytes = env->NewByteArray(static_cast<jsize>(res.coverData.size()));
        if (coverBytes) {
            env->SetByteArrayRegion(coverBytes, 0, static_cast<jsize>(res.coverData.size()),
                                    reinterpret_cast<const jbyte *>(res.coverData.data()));
        }
    }

    jobject obj = env->NewObject(cls, ctor,
        res.success,
        errStr,
        fmtStr,
        codecStr,
        static_cast<jlong>(res.durationMs),
        res.sampleRate,
        res.channelCount,
        res.sourceBitDepth,
        res.isDsd,
        res.isWavPackHybrid,
        res.dsdRateMultiplier,
        0, // flags
        titleStr,
        artistStr,
        albumStr,
        coverBytes,
        coverMimeStr
    );

    return obj;
}

JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeCreateSession(
        JNIEnv *env, jobject thiz, jobject ioBridgeObj, jint targetDsdSampleRate) {
    if (!ioBridgeObj) return 0;

    auto *ioBridge = new NativeAudioIoBridge(g_jvm, ioBridgeObj);
    SessionConfig config;
    config.targetDsdSampleRate = targetDsdSampleRate > 0 ? targetDsdSampleRate : 192000;

    auto *session = new NativeAudioSession(ioBridge, config);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jobject JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeOpenSession(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *session = reinterpret_cast<NativeAudioSession *>(handle);
    if (!session) return nullptr;

    std::string error;
    bool ok = session->open(error);

    jclass cls = env->FindClass("com/musicone/nativeaudio/OpenSessionResultRaw");
    if (!cls) return nullptr;

    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZLjava/lang/String;Ljava/lang/String;IIZIIIIJ)V");
    if (!ctor) return nullptr;

    const SessionAudioFormat &fmt = session->getFormat();
    jstring errStr = ok ? nullptr : env->NewStringUTF(error.c_str());
    jstring fmtStr = env->NewStringUTF(fmt.formatName.c_str());

    jobject obj = env->NewObject(cls, ctor,
        ok,
        errStr,
        fmtStr,
        fmt.sourceBitDepth,
        fmt.sourceSampleRate,
        fmt.isDsd,
        fmt.outputSampleRate,
        fmt.outputChannels,
        fmt.outputEncoding,
        fmt.outputBytesPerFrame,
        static_cast<jlong>(fmt.durationUs)
    );

    return obj;
}

JNIEXPORT jint JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeReadPcm(
        JNIEnv *env, jobject thiz, jlong handle, jobject directBuffer, jint maxBytes, jlongArray ptsOut) {
    auto *session = reinterpret_cast<NativeAudioSession *>(handle);
    if (!session || !directBuffer) return 0;

    uint8_t *buf = static_cast<uint8_t *>(env->GetDirectBufferAddress(directBuffer));
    if (!buf) return 0;

    int64_t ptsUs = 0;
    int bytesRead = session->readPcm(buf, maxBytes, ptsUs);

    if (ptsOut && env->GetArrayLength(ptsOut) > 0) {
        jlong ptsVal = ptsUs;
        env->SetLongArrayRegion(ptsOut, 0, 1, &ptsVal);
    }

    return bytesRead;
}

JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeSeekTo(
        JNIEnv *env, jobject thiz, jlong handle, jlong timeUs) {
    auto *session = reinterpret_cast<NativeAudioSession *>(handle);
    if (!session) return JNI_FALSE;
    return session->seekTo(timeUs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeCancelSession(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *session = reinterpret_cast<NativeAudioSession *>(handle);
    if (session) {
        session->cancel();
    }
}

JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_NativeAudioBridge_nativeCloseSession(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *session = reinterpret_cast<NativeAudioSession *>(handle);
    if (session) {
        session->close();
        delete session;
    }
}

} // extern "C"
