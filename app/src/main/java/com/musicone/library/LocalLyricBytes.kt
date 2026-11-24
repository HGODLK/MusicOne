package com.musicone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.InflaterInputStream

private val krcMagic = "krc1".toByteArray()
private val qrcMagic = "9825b0ace3028368e8fc6c".hexToByteArray()
private val krcMask = "404761775e3274475136312dced26e69".hexToByteArray()
private val qrcMask = ("c34ad6ca9067f752d8a166629f5b0900c35e95239f13117ed8923fbc90bb740e" +
    "c347743d90aa3f51d8f411849fde951dc3c609d59ffa66f9d8f0f7a090a1d6f3" +
    "c3f3d6a190a0f7f0d8f966fa9fd509c6c31d95de9f8411f4d8513faa903d7447" +
    "c30e74bb90bc3f92d87e11139f23955ec300095b9f6266a1d852f76790cad64a").hexToByteArray()
private val hexQrc = Regex("[0-9a-fA-F]+")
private const val maxInflatedLyricBytes = 8 * 1024 * 1024

internal fun parseLocalLyricBytes(bytes: ByteArray, extension: String): List<TimedLyric> {
    if (bytes.startsWithBytes(krcMagic)) {
        val raw = inflateLyric(ByteArray(bytes.size - krcMagic.size) { i ->
            (bytes[i + krcMagic.size].toInt() xor krcMask[i % krcMask.size].toInt()).toByte()
        }) ?: return emptyList()
        return parseLibraryLyrics(raw, "krc")
    }
    if (bytes.startsWithBytes(qrcMagic)) {
        val raw = decodeQrcBlocks(ByteArray(bytes.size - qrcMagic.size) { i ->
            val position = i + qrcMagic.size
            val maskIndex = (if (position > 0x7fff) position % 0x7fff else position) and 0x7f
            (bytes[position].toInt() xor qrcMask[maskIndex].toInt()).toByte()
        }) ?: return emptyList()
        return parseLibraryLyrics(raw, "qrc")
    }
    val raw = decodeLyricText(bytes)
    val hex = raw.removePrefix("\uFEFF").trim()
    if (extension == "qrc" && hex.length >= 16 && hex.length % 16 == 0 && hexQrc.matches(hex)) {
        return decodeQrcBlocks(hex.hexToByteArray())?.let { parseLibraryLyrics(it, "qrc") }.orEmpty()
    }
    return parseLibraryLyrics(raw, extension)
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun decodeLyricText(bytes: ByteArray): String {
    val bom = bytes.size >= 2 && ((bytes[0].toInt() and 255) to (bytes[1].toInt() and 255)) in
        setOf(255 to 254, 254 to 255)
    if (bom) return bytes.toString(Charsets.UTF_16)
    return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        .getOrElse { bytes.toString(Charset.forName("GB18030")) }
}

private fun decodeQrcBlocks(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size % 8 != 0) return null
    val block = ByteArray(8)
    for (i in bytes.indices step 8) {
        bytes.copyInto(block, 0, i, i + 8)
        qrcTripleDesDecrypt(block).copyInto(bytes, i)
    }
    return inflateLyric(bytes)
}

private fun inflateLyric(compressed: ByteArray): String? = runCatching {
    InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > maxInflatedLyricBytes) return null
            out.write(buffer, 0, count)
        }
        out.toByteArray().toString(Charsets.UTF_8)
    }
}.getOrNull()
