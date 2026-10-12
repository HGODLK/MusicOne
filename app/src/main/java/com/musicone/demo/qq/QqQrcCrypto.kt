package com.musicone.demo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.ISO_8859_1)

private val QRC_S_BOX = arrayOf(
    intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
    intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
    intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
    intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
    intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
    intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
    intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
    intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
)

private const val QRC_MODE_ENCRYPT = 1
private const val QRC_MODE_DECRYPT = 0

private fun qrcBitNum(a: ByteArray, b: Int, c: Int): Long {
    val idx = (b / 32) * 4 + 3 - (b % 32) / 8
    val shift = 7 - (b % 8)
    val bit = ((a[idx].toInt() and 0xff) ushr shift) and 1
    return (bit.toLong() shl c) and 0xffffffffL
}

private fun qrcBitNumIntr(a: Long, b: Int, c: Int): Long {
    val bit = (a ushr (31 - b)) and 1L
    return (bit shl c) and 0xffffffffL
}

private fun qrcBitNumIntl(a: Long, b: Int, c: Int): Long {
    val bit = (a shl b) and 0x80000000L
    return (bit ushr c) and 0xffffffffL
}

private fun qrcSBoxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

private fun qrcInitialPermutation(input: ByteArray): Pair<Long, Long> {
    var p0 = 0L
    val p0Bits = intArrayOf(
        57, 31, 49, 30, 41, 29, 33, 28, 25, 27, 17, 26, 9, 25, 1, 24,
        59, 23, 51, 22, 43, 21, 35, 20, 27, 19, 19, 18, 11, 17, 3, 16,
        61, 15, 53, 14, 45, 13, 37, 12, 29, 11, 21, 10, 13, 9, 5, 8,
        63, 7, 55, 6, 47, 5, 39, 4, 31, 3, 23, 2, 15, 1, 7, 0,
    )
    for (i in 0 until p0Bits.size step 2) {
        p0 = p0 or qrcBitNum(input, p0Bits[i], p0Bits[i + 1])
    }

    var p1 = 0L
    val p1Bits = intArrayOf(
        56, 31, 48, 30, 40, 29, 32, 28, 24, 27, 16, 26, 8, 25, 0, 24,
        58, 23, 50, 22, 42, 21, 34, 20, 26, 19, 18, 18, 10, 17, 2, 16,
        60, 15, 52, 14, 44, 13, 36, 12, 28, 11, 20, 10, 12, 9, 4, 8,
        62, 7, 54, 6, 46, 5, 38, 4, 30, 3, 22, 2, 14, 1, 6, 0,
    )
    for (i in 0 until p1Bits.size step 2) {
        p1 = p1 or qrcBitNum(input, p1Bits[i], p1Bits[i + 1])
    }
    return Pair(p0, p1)
}

private fun qrcInversePermutation(s0: Long, s1: Long): ByteArray {
    val data = ByteArray(8)
    data[3] = (qrcBitNumIntr(s1, 7, 7) or qrcBitNumIntr(s0, 7, 6) or qrcBitNumIntr(s1, 15, 5) or qrcBitNumIntr(s0, 15, 4) or qrcBitNumIntr(s1, 23, 3) or qrcBitNumIntr(s0, 23, 2) or qrcBitNumIntr(s1, 31, 1) or qrcBitNumIntr(s0, 31, 0)).toByte()
    data[2] = (qrcBitNumIntr(s1, 6, 7) or qrcBitNumIntr(s0, 6, 6) or qrcBitNumIntr(s1, 14, 5) or qrcBitNumIntr(s0, 14, 4) or qrcBitNumIntr(s1, 22, 3) or qrcBitNumIntr(s0, 22, 2) or qrcBitNumIntr(s1, 30, 1) or qrcBitNumIntr(s0, 30, 0)).toByte()
    data[1] = (qrcBitNumIntr(s1, 5, 7) or qrcBitNumIntr(s0, 5, 6) or qrcBitNumIntr(s1, 13, 5) or qrcBitNumIntr(s0, 13, 4) or qrcBitNumIntr(s1, 21, 3) or qrcBitNumIntr(s0, 21, 2) or qrcBitNumIntr(s1, 29, 1) or qrcBitNumIntr(s0, 29, 0)).toByte()
    data[0] = (qrcBitNumIntr(s1, 4, 7) or qrcBitNumIntr(s0, 4, 6) or qrcBitNumIntr(s1, 12, 5) or qrcBitNumIntr(s0, 12, 4) or qrcBitNumIntr(s1, 20, 3) or qrcBitNumIntr(s0, 20, 2) or qrcBitNumIntr(s1, 28, 1) or qrcBitNumIntr(s0, 28, 0)).toByte()
    data[7] = (qrcBitNumIntr(s1, 3, 7) or qrcBitNumIntr(s0, 3, 6) or qrcBitNumIntr(s1, 11, 5) or qrcBitNumIntr(s0, 11, 4) or qrcBitNumIntr(s1, 19, 3) or qrcBitNumIntr(s0, 19, 2) or qrcBitNumIntr(s1, 27, 1) or qrcBitNumIntr(s0, 27, 0)).toByte()
    data[6] = (qrcBitNumIntr(s1, 2, 7) or qrcBitNumIntr(s0, 2, 6) or qrcBitNumIntr(s1, 10, 5) or qrcBitNumIntr(s0, 10, 4) or qrcBitNumIntr(s1, 18, 3) or qrcBitNumIntr(s0, 18, 2) or qrcBitNumIntr(s1, 26, 1) or qrcBitNumIntr(s0, 26, 0)).toByte()
    data[5] = (qrcBitNumIntr(s1, 1, 7) or qrcBitNumIntr(s0, 1, 6) or qrcBitNumIntr(s1, 9, 5) or qrcBitNumIntr(s0, 9, 4) or qrcBitNumIntr(s1, 17, 3) or qrcBitNumIntr(s0, 17, 2) or qrcBitNumIntr(s1, 25, 1) or qrcBitNumIntr(s0, 25, 0)).toByte()
    data[4] = (qrcBitNumIntr(s1, 0, 7) or qrcBitNumIntr(s0, 0, 6) or qrcBitNumIntr(s1, 8, 5) or qrcBitNumIntr(s0, 8, 4) or qrcBitNumIntr(s1, 16, 3) or qrcBitNumIntr(s0, 16, 2) or qrcBitNumIntr(s1, 24, 1) or qrcBitNumIntr(s0, 24, 0)).toByte()
    return data
}

private fun qrcF(state: Long, key: ByteArray): Long {
    val t1 = qrcBitNumIntl(state, 31, 0) or ((state and 0xf0000000L) ushr 1) or qrcBitNumIntl(state, 4, 5) or
        qrcBitNumIntl(state, 3, 6) or ((state and 0x0f000000L) ushr 3) or qrcBitNumIntl(state, 8, 11) or
        qrcBitNumIntl(state, 7, 12) or ((state and 0x00f00000L) ushr 5) or qrcBitNumIntl(state, 12, 17) or
        qrcBitNumIntl(state, 11, 18) or ((state and 0x000f0000L) ushr 7) or qrcBitNumIntl(state, 16, 23)
    val t2 = qrcBitNumIntl(state, 15, 0) or ((state and 0x0000f000L) shl 15) or qrcBitNumIntl(state, 20, 5) or
        qrcBitNumIntl(state, 19, 6) or ((state and 0x00000f00L) shl 13) or qrcBitNumIntl(state, 24, 11) or
        qrcBitNumIntl(state, 23, 12) or ((state and 0x000000f0L) shl 11) or qrcBitNumIntl(state, 28, 17) or
        qrcBitNumIntl(state, 27, 18) or ((state and 0x0000000fL) shl 9) or qrcBitNumIntl(state, 0, 23)

    val lrg = intArrayOf(
        ((t1 ushr 24) and 0xffL).toInt() xor (key[0].toInt() and 0xff),
        ((t1 ushr 16) and 0xffL).toInt() xor (key[1].toInt() and 0xff),
        ((t1 ushr 8) and 0xffL).toInt() xor (key[2].toInt() and 0xff),
        ((t2 ushr 24) and 0xffL).toInt() xor (key[3].toInt() and 0xff),
        ((t2 ushr 16) and 0xffL).toInt() xor (key[4].toInt() and 0xff),
        ((t2 ushr 8) and 0xffL).toInt() xor (key[5].toInt() and 0xff),
    )

    var st = (QRC_S_BOX[0][qrcSBoxBit(lrg[0] ushr 2)].toLong() shl 28) or
        (QRC_S_BOX[1][qrcSBoxBit(((lrg[0] and 3) shl 4) or (lrg[1] ushr 4))].toLong() shl 24) or
        (QRC_S_BOX[2][qrcSBoxBit(((lrg[1] and 0xf) shl 2) or (lrg[2] ushr 6))].toLong() shl 20) or
        (QRC_S_BOX[3][qrcSBoxBit(lrg[2] and 0x3f)].toLong() shl 16) or
        (QRC_S_BOX[4][qrcSBoxBit(lrg[3] ushr 2)].toLong() shl 12) or
        (QRC_S_BOX[5][qrcSBoxBit(((lrg[3] and 3) shl 4) or (lrg[4] ushr 4))].toLong() shl 8) or
        (QRC_S_BOX[6][qrcSBoxBit(((lrg[4] and 0xf) shl 2) or (lrg[5] ushr 6))].toLong() shl 4) or
        QRC_S_BOX[7][qrcSBoxBit(lrg[5] and 0x3f)].toLong()

    return (qrcBitNumIntl(st, 15, 0) or qrcBitNumIntl(st, 6, 1) or qrcBitNumIntl(st, 19, 2) or
        qrcBitNumIntl(st, 20, 3) or qrcBitNumIntl(st, 28, 4) or qrcBitNumIntl(st, 11, 5) or
        qrcBitNumIntl(st, 27, 6) or qrcBitNumIntl(st, 16, 7) or qrcBitNumIntl(st, 0, 8) or
        qrcBitNumIntl(st, 14, 9) or qrcBitNumIntl(st, 22, 10) or qrcBitNumIntl(st, 25, 11) or
        qrcBitNumIntl(st, 4, 12) or qrcBitNumIntl(st, 17, 13) or qrcBitNumIntl(st, 30, 14) or
        qrcBitNumIntl(st, 9, 15) or qrcBitNumIntl(st, 1, 16) or qrcBitNumIntl(st, 7, 17) or
        qrcBitNumIntl(st, 23, 18) or qrcBitNumIntl(st, 13, 19) or qrcBitNumIntl(st, 31, 20) or
        qrcBitNumIntl(st, 26, 21) or qrcBitNumIntl(st, 2, 22) or qrcBitNumIntl(st, 8, 23) or
        qrcBitNumIntl(st, 18, 24) or qrcBitNumIntl(st, 12, 25) or qrcBitNumIntl(st, 29, 26) or
        qrcBitNumIntl(st, 5, 27) or qrcBitNumIntl(st, 21, 28) or qrcBitNumIntl(st, 10, 29) or
        qrcBitNumIntl(st, 3, 30) or qrcBitNumIntl(st, 24, 31)) and 0xffffffffL
}

private fun qrcDesCrypt(input: ByteArray, keys: Array<ByteArray>): ByteArray {
    var (s0, s1) = qrcInitialPermutation(input)
    for (i in 0 until 15) {
        val prev = s1
        s1 = (qrcF(s1, keys[i]) xor s0) and 0xffffffffL
        s0 = prev
    }
    s0 = (qrcF(s1, keys[15]) xor s0) and 0xffffffffL
    return qrcInversePermutation(s0, s1)
}

private fun qrcKeySchedule(key: ByteArray, mode: Int): Array<ByteArray> {
    val schedule = Array(16) { ByteArray(6) }
    val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    val keyPermC = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
    val keyPermD = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    val keyCompression = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )
    var c = 0L
    var d = 0L
    for (i in 0 until 28) {
        c = c or qrcBitNum(key, keyPermC[i], 31 - i)
        d = d or qrcBitNum(key, keyPermD[i], 31 - i)
    }
    for (i in 0 until 16) {
        val shift = keyRndShift[i]
        c = (((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0L)
        d = (((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0L)
        val toGen = if (mode == QRC_MODE_DECRYPT) 15 - i else i
        for (j in 0 until 24) {
            schedule[toGen][j / 8] = (schedule[toGen][j / 8].toInt() or qrcBitNumIntr(c, keyCompression[j], 7 - (j % 8)).toInt()).toByte()
        }
        for (j in 24 until 48) {
            schedule[toGen][j / 8] = (schedule[toGen][j / 8].toInt() or qrcBitNumIntr(d, keyCompression[j] - 27, 7 - (j % 8)).toInt()).toByte()
        }
    }
    return schedule
}

private fun qrcTripleDesDecrypt(block: ByteArray): ByteArray {
    val k0 = qrcKeySchedule(QRC_KEY.copyOfRange(16, 24), QRC_MODE_DECRYPT)
    val k1 = qrcKeySchedule(QRC_KEY.copyOfRange(8, 16), QRC_MODE_ENCRYPT)
    val k2 = qrcKeySchedule(QRC_KEY.copyOfRange(0, 8), QRC_MODE_DECRYPT)
    var d = block.copyOf()
    d = qrcDesCrypt(d, k0)
    d = qrcDesCrypt(d, k1)
    d = qrcDesCrypt(d, k2)
    return d
}

internal fun decryptQqQrcHex(rawHex: String): String = runCatching {
    val clean = rawHex.trim()
    if (clean.length < 16 || clean.length % 16 != 0) return@runCatching ""
    val len = clean.length / 2
    val encrypted = ByteArray(len)
    for (i in 0 until len) {
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return@runCatching ""
        encrypted[i] = ((hi shl 4) or lo).toByte()
    }
    val plain = ByteArray(len)
    val block = ByteArray(8)
    for (i in 0 until len step 8) {
        System.arraycopy(encrypted, i, block, 0, 8)
        val decrypted = qrcTripleDesDecrypt(block)
        System.arraycopy(decrypted, 0, plain, i, 8)
    }
    val out = ByteArrayOutputStream()
    InflaterInputStream(ByteArrayInputStream(plain)).use { input ->
        val buffer = ByteArray(2048)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
    }
    out.toString("UTF-8")
}.getOrDefault("")
