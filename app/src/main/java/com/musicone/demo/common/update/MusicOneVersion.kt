package com.musicone.demo

/**
 * 版本数据模型，包含三段整数主版本号与可选代号（如 0.2.0-麦乐鸡块）。
 * 版本大小比较仅基于 major、minor、patch 三段整数，代号不参与大小排序。
 */
internal data class MusicOneVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val nickname: String? = null,
) : Comparable<MusicOneVersion> {
    val coreLabel: String get() = "$major.$minor.$patch"
    val label: String get() = if (nickname.isNullOrBlank()) coreLabel else "$coreLabel-$nickname"

    override fun compareTo(other: MusicOneVersion): Int =
        compareValuesBy(this, other, MusicOneVersion::major, MusicOneVersion::minor, MusicOneVersion::patch)

    companion object {
        // 匹配三段整数版本号及可选的短横线代号（汉字/西文字数不限）
        private val pattern = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-(.+))?")

        fun parse(value: String): MusicOneVersion? {
            val trimmed = value.trim()
            val match = pattern.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toLongOrNull() ?: return null
            val minor = match.groupValues[2].toLongOrNull() ?: return null
            val patch = match.groupValues[3].toLongOrNull() ?: return null
            val nickname = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
            return MusicOneVersion(major, minor, patch, nickname)
        }

        fun installed(value: String): MusicOneVersion? {
            val normalized = value.trim().removeSuffix("-debugrelease")
            return parse(normalized)
        }
    }
}
