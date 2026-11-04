package com.musicone

import com.musicone.source.sdk.SourceDescriptor
import java.util.concurrent.ConcurrentHashMap

/** 稳定音源身份独立于包安装状态；缺包时仍可保存、显示和恢复既有曲目。 */
class MusicSource private constructor(
    val id: String,
    val name: String,
    private val fallbackLabel: String,
    private val fallbackAccent: Long,
    val ordinal: Int,
) {
    @Volatile private var descriptor: SourceDescriptor? = null
    val label: String get() = descriptor?.name ?: fallbackLabel
    val accent: Long get() = descriptor?.presentation?.pageColorArgb ?: fallbackAccent
    internal val idPrefix: String get() = "${name.lowercase()}-"
    internal fun configure(value: SourceDescriptor) { require(value.id == id); descriptor = value }
    override fun equals(other: Any?): Boolean = other is MusicSource && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        // 这些常量是旧持久化身份映射，迁移期间不改名、不改变原缓存及队列的归属。
        val NETEASE = MusicSource("com.musicone.source.netease", "NETEASE", "网易云音乐", 0xFFEC4141, 0)
        val QQ = MusicSource("com.musicone.source.qq", "QQ", "QQ 音乐", 0xFF31C27C, 1)
        val KUGOU = MusicSource("com.musicone.source.kugou", "KUGOU", "酷狗音乐", 0xFF009AF3, 2)
        val LOCAL = MusicSource("local", "LOCAL", "本地音乐", 0xFF9B8AC4, 3)
        val BILIBILI = MusicSource("com.musicone.source.bilibili", "BILIBILI", "哔哩哔哩", 0xFFFB7299, 4)
        private val sources = ConcurrentHashMap(listOf(NETEASE, QQ, KUGOU, LOCAL, BILIBILI).associateBy { it.id })
        val entries: List<MusicSource> get() = sources.values.sortedWith(compareBy<MusicSource> { it.ordinal }.thenBy { it.id })

        fun fromId(id: String): MusicSource {
            require(id == "local" || id.matches(Regex("[a-z][a-z0-9_.-]{2,127}"))) { "音源身份无效" }
            return sources.getOrPut(id) { MusicSource(id, id, id, 0L, 5) }
        }
        fun register(descriptor: SourceDescriptor): MusicSource = fromId(descriptor.id).also { it.configure(descriptor) }
        fun valueOf(name: String): MusicSource = sources.values.firstOrNull { it.name == name || it.id == name }
            ?: fromId(name)
    }
}
