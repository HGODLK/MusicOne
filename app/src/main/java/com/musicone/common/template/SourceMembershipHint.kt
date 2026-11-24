package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * 上次在线核对歌曲权益时得到的会员结论，按“音源:账号”保存。
 * 冷启动时音源还没联网查会员，歌曲角标先按它推测，避免会员账号先闪一下 VIP 再消失；
 * 只用于首帧推测，核对完成后仍以音源返回的权益为准。
 */
internal object SourceMembershipHint {
    private const val FILE = "source_membership_hint"
    private val memory = ConcurrentHashMap<String, Boolean>()

    private fun key(entry: SourceEntry?, source: MusicSource): String? =
        entry?.authentication?.currentAccount()?.account?.id?.takeIf { it.isNotBlank() }?.let { "${source.id}:$it" }

    fun read(context: Context, entry: SourceEntry?, source: MusicSource): Boolean? {
        val key = key(entry, source) ?: return null
        memory[key]?.let { return it }
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!store.contains(key)) return null
        return store.getBoolean(key, false).also { memory[key] = it }
    }

    /** 列表里每一行都会核对，同样的结论只写一次。 */
    fun record(context: Context, entry: SourceEntry?, source: MusicSource, member: Boolean) {
        val key = key(entry, source) ?: return
        if (memory.put(key, member) == member) return
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(key, member).apply()
    }
}
