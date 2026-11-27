package com.musicone

/** 只统计 HTTP 实际 read 音频正文的耗时来估算网速，建连、读缓存、解码和暂停等待都不算。 */
internal class PlaybackPreloadBandwidth {
    private data class Read(val bucket: Long, var endNs: Long, var durationNs: Long, var bytes: Long)
    private class Transfer(val uri: String) {
        val reads = ArrayDeque<Read>()
        var readingAt: Long? = null
    }
    private val mediaLoads = mutableMapOf<Long, String>()
    private val transfers = mutableMapOf<Long, Transfer>()
    private var sequence = 0L
    var failureRevision = 0L
        @Synchronized get
        private set

    @Synchronized fun mediaLoad(id: Long, uri: String) { mediaLoads[id] = uri }
    @Synchronized fun finishMediaLoad(id: Long) { mediaLoads.remove(id) }
    @Synchronized fun opened(uri: String): Long = (++sequence).also { transfers[it] = Transfer(uri) }
    @Synchronized fun beginRead(id: Long, nowNs: Long) { transfers[id]?.readingAt = nowNs }
    @Synchronized fun endRead(id: Long, bytes: Int, nowNs: Long) {
        val transfer = transfers[id] ?: return
        val began = transfer.readingAt ?: return
        transfer.readingAt = null
        if (bytes > 0) {
            val bucket = nowNs / 250_000_000L
            val duration = (nowNs - began).coerceAtLeast(0L)
            val previous = transfer.reads.lastOrNull()?.takeIf { it.bucket == bucket }
            if (previous == null) transfer.reads.addLast(Read(bucket, nowNs, duration, bytes.toLong()))
            else { previous.endNs = nowNs; previous.durationNs += duration; previous.bytes += bytes }
        }
        trim(transfer, nowNs)
    }
    @Synchronized fun failed(id: Long) {
        val transfer = transfers.remove(id) ?: return
        if (transfer.uri in mediaLoads.values) failureRevision++
    }
    @Synchronized fun closed(id: Long) { transfers.remove(id) }

    @Synchronized fun resetMeasurements() {
        transfers.values.forEach { it.reads.clear(); it.readingAt = null }
    }
    @Synchronized fun newMedia() { mediaLoads.clear(); resetMeasurements() }

    /** 码率未知、样本不足或累计读取不到 2 秒时，不贸然判定为带宽不足。 */
    @Synchronized fun insufficient(nowNs: Long, requiredBitsPerSecond: Long): Boolean {
        if (requiredBitsPerSecond <= 0L) return false
        val active = transfers.values.filter { it.uri in mediaLoads.values }
        val transfer = active.singleOrNull() ?: return false
        val readingAt = transfer.readingAt ?: return false
        trim(transfer, nowNs)
        val bytes = transfer.reads.sumOf { it.bytes }
        if (bytes <= 0L) return false
        val pendingNs = (nowNs - maxOf(readingAt, nowNs - WINDOW_NS)).coerceAtLeast(0L)
        val durationNs = transfer.reads.sumOf { it.durationNs } + pendingNs
        if (durationNs < 2_000_000_000L) return false
        val bitsPerSecond = bytes.toDouble() * 8_000_000_000.0 / durationNs
        return bitsPerSecond < requiredBitsPerSecond * 0.7
    }

    private fun trim(transfer: Transfer, nowNs: Long) {
        while (transfer.reads.firstOrNull()?.let { nowNs - it.endNs > WINDOW_NS } == true) {
            transfer.reads.removeFirst()
        }
    }
    private companion object { const val WINDOW_NS = 5_000_000_000L }
}
