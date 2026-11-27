package com.musicone

import android.os.Trace

/** 写入系统 Trace 耗时标记，方便性能分析（不包含歌曲、地址或账号信息）。 */
internal inline fun <T> playbackTrace(section: String, block: () -> T): T {
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
