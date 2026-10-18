package com.musicone.demo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** 静音占位轨走内置扬声器的普通媒体通道，真实音乐仍由既有 USB 输出链路处理。 */
internal fun openUsbSilentAudioTrack(context: Context): UsbSilenceTrack {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: error("静音占位轨未找到内置扬声器")
    val minimum = AudioTrack.getMinBufferSize(UsbSilenceFeeder.SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0) { "静音占位轨不支持指定 PCM 格式" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(UsbSilenceFeeder.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(minimum * 2, UsbSilenceFeeder.SAMPLES_PER_CHUNK * 2 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "静音占位轨初始化失败" }
        check(track.setPreferredDevice(speaker)) { "静音占位轨无法选择内置扬声器" }
    } catch (failure: Exception) { track.release(); throw failure }
    return object : UsbSilenceTrack {
        override fun play() = track.play()
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            // 首次供数前路由可能尚未建立；建立后必须核对，不能把占位数据混入 USB 或蓝牙输出。
            val routed = track.routedDevice
            check(routed == null || routed.id == speaker.id) { "静音占位轨未使用内置扬声器" }
            return track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
        }
        override fun pause() = track.pause()
        override fun release() {
            try { runCatching { track.stop() } } finally { track.release() }
        }
    }
}
