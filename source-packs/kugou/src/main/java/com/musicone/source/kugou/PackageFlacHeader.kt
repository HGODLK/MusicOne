package com.musicone.source.kugou

import java.net.HttpURLConnection
import java.net.URI

/** 只读取文件头；部分系统将 FLAC 报告为 audio/raw，不能据此判成试听。 */
internal fun readPackageFlacDuration(url: String): Long? = readPackageFlacInfo(url)?.durationMs

internal data class PackageFlacInfo(val durationMs: Long, val sampleRate: Int, val bitDepth: Int) {
    val description: String get() = "FLAC · ${bitDepth}bit / ${java.math.BigDecimal(sampleRate).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
    val highResolution: Boolean get() = bitDepth > 16 && sampleRate >= 44100
}

internal fun readPackageFlacInfo(url: String): PackageFlacInfo? = readPackageFlacInfo(url, timeoutMs = 4_000)

internal fun readPackageFlacInfo(url: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): PackageFlacInfo? = runCatchingCancellable {
    val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("Range", "bytes=0-41")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (connection.responseCode !in 200..299) return@runCatchingCancellable null
        connection.inputStream.use { stream ->
            val header = ByteArray(42)
            var count = 0
            while (count < header.size) {
                val read = stream.read(header, count, header.size - count)
                if (read < 0) break
                count += read
            }
            packageFlacInfo(header.copyOf(count))
        }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

internal fun packageFlacDuration(header: ByteArray): Long? = packageFlacInfo(header)?.durationMs

internal fun packageFlacInfo(header: ByteArray): PackageFlacInfo? {
    if (header.size < 42 || header.take(4) != listOf<Byte>(102, 76, 97, 67) ||
        header[4].toInt() and 127 != 0 || header[5].toInt() != 0 ||
        header[6].toInt() != 0 || header[7].toInt() != 34) return null
    var packed = 0L
    for (index in 18..25) packed = (packed shl 8) or (header[index].toLong() and 255)
    val sampleRate = (packed ushr 44) and 0xfffff
    val samples = packed and 0xfffffffff
    val bitDepth = ((packed ushr 36) and 31).toInt() + 1
    return if (sampleRate > 0 && samples > 0) PackageFlacInfo(samples * 1_000 / sampleRate, sampleRate.toInt(), bitDepth) else null
}

