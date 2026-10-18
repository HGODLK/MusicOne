package com.musicone.demo

/** 每轮最多准备三张、合计两 MiB；超大资源保留正常按需绘制路径。 */
internal fun lyricRasterPreparationIndices(bytes: List<Long>): List<Int> {
    val selected = ArrayList<Int>(3)
    var remaining = 2L * 1024L * 1024L
    for ((index, size) in bytes.withIndex()) {
        if (size <= 0L || size > remaining) continue
        selected += index
        remaining -= size
        if (selected.size == 3) break
    }
    return selected
}
