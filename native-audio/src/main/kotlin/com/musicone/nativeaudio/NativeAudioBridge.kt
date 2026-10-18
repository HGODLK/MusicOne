package com.musicone.nativeaudio

import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
internal object NativeAudioBridge {
    private var isLoaded = false
    private val loadLock = Any()

    fun ensureLoaded() {
        if (isLoaded) return
        synchronized(loadLock) {
            if (!isLoaded) {
                System.loadLibrary("native-audio")
                isLoaded = true
            }
        }
    }

    external fun nativeProbe(ioBridge: Any): ProbeResultRaw?
    external fun nativeCreateSession(ioBridge: Any, targetDsdSampleRate: Int): Long
    external fun nativeOpenSession(handle: Long): OpenSessionResultRaw?
    external fun nativeReadPcm(handle: Long, directBuffer: ByteBuffer, maxBytes: Int, ptsOut: LongArray): Int
    external fun nativeSeekTo(handle: Long, timeUs: Long): Boolean
    external fun nativeCancelSession(handle: Long)
    external fun nativeCloseSession(handle: Long)
}
