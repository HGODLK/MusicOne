package com.musicone.demo

import android.os.Trace

/** 与系统帧时间对齐的播放阶段标记，不记录歌曲、地址或账号信息。 */
internal inline fun <T> playbackTrace(section: String, block: () -> T): T {
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
