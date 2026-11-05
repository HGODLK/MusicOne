package com.musicone

/** 识别控制层迟到的文本回声；输入法的光标和组合态始终由本地编辑值保留。 */
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
