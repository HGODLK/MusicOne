// 自然过渡引擎的 JNI 入口：Kotlin 只负责解码、YAMNet 推理和播放接入，分析、排程、渲染都在这里。
#include <jni.h>

#include <android/log.h>

#include <numeric>
#include <string>

#include "am_mix.h"
#include "am_store.h"

#include <cstring>

using namespace automix;

namespace {

jfloatArray toInterleaved(JNIEnv *env, const Stereo &s) {
    jfloatArray out = env->NewFloatArray((jsize)(s.n() * 2));
    if (!out) return nullptr;
    std::vector<float> buf(s.n() * 2);
    for (size_t i = 0; i < s.n(); ++i) { buf[2 * i] = s.L[i]; buf[2 * i + 1] = s.R[i]; }
    env->SetFloatArrayRegion(out, 0, (jsize)buf.size(), buf.data());
    return out;
}

Stereo resampleTo(const Stereo &x, int inRate, int outRate) {
    if (inRate == outRate) return x;
    int g = std::gcd(inRate, outRate);
    return resamplePoly(x, outRate / g, inRate / g);
}

jdoubleArray toDoubles(JNIEnv *env, const vecd &v) {
    jdoubleArray out = env->NewDoubleArray((jsize)v.size());
    if (out) env->SetDoubleArrayRegion(out, 0, (jsize)v.size(), v.data());
    return out;
}

void setDouble(JNIEnv *env, jobject obj, jclass cls, const char *name, double v) {
    env->SetDoubleField(obj, env->GetFieldID(cls, name, "D"), v);
}

void setInt(JNIEnv *env, jobject obj, jclass cls, const char *name, int v) {
    env->SetIntField(obj, env->GetFieldID(cls, name, "I"), v);
}

void setObject(JNIEnv *env, jobject obj, jclass cls, const char *name, const char *sig, jobject v) {
    env->SetObjectField(obj, env->GetFieldID(cls, name, sig), v);
}

}  // namespace

// —— 离线解码存储：整首 PCM 留在原生内存，Java 侧只持有句柄 ——

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreCreate(JNIEnv *, jobject, jint inRate, jboolean keepTail,
                                                                      jdouble seconds) {
    return reinterpret_cast<jlong>(new PcmStore(inRate, keepTail == JNI_TRUE, seconds));
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStorePush(JNIEnv *env, jobject, jlong handle, jfloatArray pcm,
                                                                    jint frames) {
    auto *store = reinterpret_cast<PcmStore *>(handle);
    auto *p = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (!p) return;
    store->push(p, frames);
    env->ReleasePrimitiveArrayCritical(pcm, p, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreHeadComplete(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<PcmStore *>(handle)->headComplete() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreFinish(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<PcmStore *>(handle)->finish();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreOffset(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<PcmStore *>(handle)->offset();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreFrames(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<PcmStore *>(handle)->frames();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreTotalOut(JNIEnv *, jobject, jlong handle, jlong nativeFrames) {
    return reinterpret_cast<PcmStore *>(handle)->totalOut(nativeFrames);
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeStoreRelease(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<PcmStore *>(handle);
}

// —— YAMNet 输入：16kHz 单声道留在原生内存，按窗口拷进推理输入缓冲 ——

extern "C" JNIEXPORT jlong JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeVoxCreate(JNIEnv *, jobject, jlong store, jlong startFrame,
                                                                    jlong frames) {
    auto *s = reinterpret_cast<PcmStore *>(store);
    return reinterpret_cast<jlong>(new std::vector<float>(voxInput16k(s->slice(startFrame, frames), WORK_SR)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeVoxLength(JNIEnv *, jobject, jlong vox) {
    return (jint)reinterpret_cast<std::vector<float> *>(vox)->size();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeVoxWindow(JNIEnv *env, jobject, jlong vox, jint offset,
                                                                    jint window, jobject dst) {
    auto *m = reinterpret_cast<std::vector<float> *>(vox);
    void *addr = env->GetDirectBufferAddress(dst);
    if (!addr || offset < 0 || (size_t)offset + (size_t)window > m->size() ||
        env->GetDirectBufferCapacity(dst) < (jlong)window * (jlong)sizeof(float)) return JNI_FALSE;
    std::memcpy(addr, m->data() + offset, (size_t)window * sizeof(float));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeVoxRelease(JNIEnv *, jobject, jlong vox) {
    delete reinterpret_cast<std::vector<float> *>(vox);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_musicone_nativeaudio_automix_AutoMixNative_nativeMix(
        JNIEnv *env, jobject, jlong aStore, jlong aTotal, jlong bStore, jlong bTotal,
        jfloatArray embA, jint framesA, jfloatArray embB, jint framesB, jbyteArray head, jint outRate,
        jobject result) {
    VoxHead voxHead;
    {
        jsize n = env->GetArrayLength(head);
        std::vector<char> buf((size_t)n);
        env->GetByteArrayRegion(head, 0, n, reinterpret_cast<jbyte *>(buf.data()));
        if (!voxHead.loadMemory(buf.data(), buf.size())) return nullptr;
    }
    MixInput in;
    // 两个存储的数据直接移交给引擎（之后存储为空，由 Java 侧照常释放）
    auto *sa = reinterpret_cast<PcmStore *>(aStore);
    auto *sb = reinterpret_cast<PcmStore *>(bStore);
    in.a.offset = sa->offset();
    in.a.x = sa->take();
    in.a.total = (long)aTotal;
    in.b.x = sb->take();
    in.b.offset = 0;
    in.b.total = (long)bTotal;
    in.a.sr = in.b.sr = WORK_SR;
    auto readEmb = [&](jfloatArray arr, std::vector<float> &dst) {
        jsize n = env->GetArrayLength(arr);
        dst.resize((size_t)n);
        env->GetFloatArrayRegion(arr, 0, n, dst.data());
        for (auto &v : dst) v = roundHalf(v);   // 训练与实验代码都按 float16 保存嵌入
    };
    readEmb(embA, in.embA);
    readEmb(embB, in.embB);
    in.framesA = framesA;
    in.framesB = framesB;
    in.head = &voxHead;
    MixOptions opt;
    opt.render = true;
    opt.faithful = false;
    MixResult r = automix::automix(in, opt);
    std::string log;
    for (auto &l : r.log) { log += l; log += '\n'; }
    __android_log_print(ANDROID_LOG_INFO, "MusicOneAutoMix", "%s", log.c_str());

    jclass cls = env->GetObjectClass(result);
    setInt(env, result, cls, "kind", (int)r.kind);
    setObject(env, result, cls, "branch", "Ljava/lang/String;", env->NewStringUTF(r.branch.c_str()));
    setObject(env, result, cls, "log", "Ljava/lang/String;", env->NewStringUTF(log.c_str()));
    setDouble(env, result, cls, "seamA", r.seamA);
    setDouble(env, result, cls, "bCue", r.bCue);
    setInt(env, result, cls, "overlapBars", r.overlapBars);
    setDouble(env, result, cls, "rateA", r.rateA);
    setDouble(env, result, cls, "rateB", r.rateB);
    if (r.kind != MixKind::None && r.clip.n() > 0) {
        Stereo clip = resampleTo(r.clip, WORK_SR, outRate);
        setObject(env, result, cls, "clip", "[F", toInterleaved(env, clip));
        setDouble(env, result, cls, "clipStartA", r.clipStartA);
        setDouble(env, result, cls, "enter", r.enter);
        setDouble(env, result, cls, "seam", r.seam);
        setDouble(env, result, cls, "fromEnd", r.fromEnd);
        setDouble(env, result, cls, "total", (double)clip.n() / outRate);
        setDouble(env, result, cls, "aDryUntil", r.aDryUntil);
        setDouble(env, result, cls, "bEndSrc", r.bEndSrc);
        setObject(env, result, cls, "mapA", "[D", toDoubles(env, r.mapA));
        setObject(env, result, cls, "mapB", "[D", toDoubles(env, r.mapB));
        setObject(env, result, cls, "visual", "[D", toDoubles(env, r.visual));
    }
    return result;
}
