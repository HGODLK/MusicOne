package com.musicone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** 打开一条走内置扬声器的静音 AudioTrack 占位（用来接管系统音量键），真实音乐仍走 USB 独占输出。 */
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
            // 刚开始写数据时路由可能还没就绪，一旦就绪就校验必须走扬声器，别混进 USB 或蓝牙。
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
