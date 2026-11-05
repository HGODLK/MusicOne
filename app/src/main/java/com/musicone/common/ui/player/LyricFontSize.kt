package com.musicone

/** 歌词四档大小；中档保持原排版，翻译和注音只跟随一半幅度，避免小档看不清。 */
internal enum class LyricFontSize(val storedValue: String, val label: String, val scale: Float) {
    EXTRA_SMALL("extra_small", "很小", .7f),
    SMALL("small", "小", .85f),
    MEDIUM("medium", "中", 1f),
    LARGE("large", "大", 1.125f);

    val secondaryScale: Float get() = 1f + (scale - 1f) / 2f

    companion object {
        fun fromStored(value: String?): LyricFontSize = entries.firstOrNull { it.storedValue == value } ?: MEDIUM
    }
}
