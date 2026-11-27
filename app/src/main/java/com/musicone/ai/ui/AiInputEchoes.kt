package com.musicone

/** 过滤掉 ViewModel 迟到回推的旧文本，防止覆盖掉输入框当前的光标和输入法候选词。 */
internal class AiInputEchoes {
    private val pending = ArrayDeque<String>()
    fun sent(text: String) { pending.addLast(text) }
    fun consume(text: String): Boolean {
        val index = pending.indexOfLast { it == text }
        if (index < 0) { pending.clear(); return false }
        repeat(index + 1) { pending.removeFirst() }
        return true
    }
}
