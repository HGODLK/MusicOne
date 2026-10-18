#include "native_audio_io.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "NativeAudioIo"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int avio_read_callback(void *opaque, uint8_t *buf, int buf_size) {
    auto *bridge = static_cast<NativeAudioIoBridge *>(opaque);
    return bridge->read(buf, buf_size);
}

static int64_t avio_seek_callback(void *opaque, int64_t offset, int whence) {
    auto *bridge = static_cast<NativeAudioIoBridge *>(opaque);
    return bridge->seek(offset, whence);
}

NativeAudioIoBridge::NativeAudioIoBridge(JavaVM *vm, jobject ioBridgeObj)
    : jvm_(vm) {
    JNIEnv *env = getEnv();
    if (env && ioBridgeObj) {
        ioBridgeRef_ = env->NewGlobalRef(ioBridgeObj);
        jclass clazz = env->GetObjectClass(ioBridgeObj);
        readMethod_ = env->GetMethodID(clazz, "read", "([BII)I");
        seekMethod_ = env->GetMethodID(clazz, "seek", "(J)J");
        getSizeMethod_ = env->GetMethodID(clazz, "getSize", "()J");
        if (getSizeMethod_) {
            totalSize_ = env->CallLongMethod(ioBridgeRef_, getSizeMethod_);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                totalSize_ = -1;
            }
        }
    }
}

NativeAudioIoBridge::~NativeAudioIoBridge() {
    JNIEnv *env = getEnv();
    if (env) {
        if (reusableByteArray_) {
            env->DeleteGlobalRef(reusableByteArray_);
            reusableByteArray_ = nullptr;
        }
        if (ioBridgeRef_) {
            env->DeleteGlobalRef(ioBridgeRef_);
            ioBridgeRef_ = nullptr;
        }
    }
}

JNIEnv *NativeAudioIoBridge::getEnv() {
    if (!jvm_) return nullptr;
    JNIEnv *env = nullptr;
    jint res = jvm_->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (jvm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

AVIOContext *NativeAudioIoBridge::createAvioContext(int bufferSize) {
    unsigned char *buffer = static_cast<unsigned char *>(av_malloc(bufferSize));
    if (!buffer) return nullptr;

    AVIOContext *avio = avio_alloc_context(
        buffer, bufferSize, 0, this,
        avio_read_callback, nullptr, avio_seek_callback);
    if (!avio) {
        av_free(buffer);
        return nullptr;
    }
    avio->seekable = AVIO_SEEKABLE_NORMAL;
    return avio;
}

int64_t NativeAudioIoBridge::getSize() const {
    return totalSize_;
}

void NativeAudioIoBridge::cancel() {
    cancelled_.store(true);
}

int NativeAudioIoBridge::read(uint8_t *buf, int size) {
    if (cancelled_.load()) return AVERROR_EXIT;
    if (size <= 0) return 0;

    JNIEnv *env = getEnv();
    if (!env || !ioBridgeRef_ || !readMethod_) return AVERROR_EXTERNAL;

    if (!reusableByteArray_ || reusableArrayCapacity_ < size) {
        if (reusableByteArray_) {
            env->DeleteGlobalRef(reusableByteArray_);
        }
        jbyteArray local = env->NewByteArray(size);
        if (!local) return AVERROR(ENOMEM);
        reusableByteArray_ = static_cast<jbyteArray>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        reusableArrayCapacity_ = size;
    }

    jint bytesRead = env->CallIntMethod(ioBridgeRef_, readMethod_, reusableByteArray_, 0, size);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return AVERROR_EXTERNAL;
    }

    if (bytesRead < 0) {
        return AVERROR_EOF;
    }
    if (bytesRead > 0) {
        env->GetByteArrayRegion(reusableByteArray_, 0, bytesRead, reinterpret_cast<jbyte *>(buf));
        currentPosition_ += bytesRead;
    }
    return bytesRead;
}

int64_t NativeAudioIoBridge::seek(int64_t offset, int whence) {
    if (cancelled_.load()) return AVERROR_EXIT;

    if (whence == AVSEEK_SIZE) {
        return totalSize_;
    }

    int64_t target = -1;
    switch (whence) {
        case SEEK_SET:
            target = offset;
            break;
        case SEEK_CUR:
            target = currentPosition_ + offset;
            break;
        case SEEK_END:
            if (totalSize_ >= 0) target = totalSize_ + offset;
            break;
        default:
            return AVERROR(EINVAL);
    }

    if (target < 0) return AVERROR(EINVAL);

    JNIEnv *env = getEnv();
    if (!env || !ioBridgeRef_ || !seekMethod_) return AVERROR_EXTERNAL;

    jlong newPos = env->CallLongMethod(ioBridgeRef_, seekMethod_, static_cast<jlong>(target));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return AVERROR_EXTERNAL;
    }

    currentPosition_ = newPos;
    return newPos;
}
