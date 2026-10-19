package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class CachedAudioDetailsTest {
    @Test
    fun 同曲跨歌单引用不重复计算物理字节() {
        val shared = CachedAudioResource("shared", "QQ|song", AudioQuality.EXHIGH, 3_145_728)
        val detail = cachedAudioDetails(listOf(shared, shared)).getValue("QQ|song")
        assertEquals(3_145_728L, detail.bytes)
        assertEquals(listOf(AudioQuality.EXHIGH), detail.qualities)
        assertFalse(detail.hasUnknownQuality)
    }

    @Test
    fun 多个物理资源按实际占用合计并保留全部缓存档位() {
        val detail = cachedAudioDetails(listOf(
            CachedAudioResource("mp3", "QQ|song", AudioQuality.EXHIGH, 1024),
            CachedAudioResource("flac", "QQ|song", AudioQuality.LOSSLESS, 2048),
            CachedAudioResource("mp3-part", "QQ|song", AudioQuality.EXHIGH, 512),
        )).getValue("QQ|song")
        assertEquals(3584L, detail.bytes)
        assertEquals(listOf(AudioQuality.LOSSLESS, AudioQuality.EXHIGH), detail.qualities)
    }

    @Test
    fun 没有落盘字节不显示缓存音质且不跨音源合并() {
        val details = cachedAudioDetails(listOf(
            CachedAudioResource("empty", "QQ|empty", AudioQuality.HI_RES, 0),
            CachedAudioResource("qq", "QQ|song", AudioQuality.STANDARD, 256),
            CachedAudioResource("kg", "KUGOU|song", AudioQuality.LOSSLESS, 512),
        ))
        assertFalse(details.containsKey("QQ|empty"))
        assertEquals(listOf(AudioQuality.STANDARD), details.getValue("QQ|song").qualities)
        assertEquals(256L, details.getValue("QQ|song").bytes)
        assertEquals(512L, details.getValue("KUGOU|song").bytes)
    }

    @Test
    fun 旧资源缺少音质时保留真实占用并明确未知() {
        val detail = cachedAudioDetails(listOf(CachedAudioResource("legacy", "QQ|song", null, 1024))).getValue("QQ|song")
        assertTrue(detail.qualities.isEmpty())
        assertEquals(1024L, detail.bytes)
        assertTrue(detail.hasUnknownQuality)
    }
}
