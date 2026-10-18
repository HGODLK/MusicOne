#ifndef NATIVE_AUDIO_IO_H
#define NATIVE_AUDIO_IO_H

#include <jni.h>
#include <cstdint>
#include <cerrno>
#include <atomic>

extern "C" {
#include <libavformat/avio.h>
#include <libavutil/mem.h>
#include <libavutil/error.h>
}

class NativeAudioIoBridge {
public:
    NativeAudioIoBridge(JavaVM *vm, jobject ioBridgeObj);
    ~NativeAudioIoBridge();

    AVIOContext *createAvioContext(int bufferSize = 32768);
    int64_t getSize() const;
    void cancel();
    void resetCancelled() { cancelled_.store(false); }
    bool isCancelled() const { return cancelled_.load(); }

    // 回调给 FFmpeg
    int read(uint8_t *buf, int size);
    int64_t seek(int64_t offset, int whence);

private:
    JavaVM *jvm_{nullptr};
    jobject ioBridgeRef_{nullptr};
    jmethodID readMethod_{nullptr};
    jmethodID seekMethod_{nullptr};
    jmethodID getSizeMethod_{nullptr};
    jbyteArray reusableByteArray_{nullptr};
    int reusableArrayCapacity_{0};
    std::atomic<bool> cancelled_{false};
    int64_t currentPosition_{0};
    int64_t totalSize_{-1};

    JNIEnv *getEnv();
};

#endif // NATIVE_AUDIO_IO_H
