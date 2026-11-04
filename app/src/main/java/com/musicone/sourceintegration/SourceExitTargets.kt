package com.musicone.sourceintegration

/** 仅在主线程登记可交互外页；退出请求只能交给同一音源的最上层页面。 */
internal class SourceExitTargets {
    private class Target(val sourceId: String, val exit: () -> Unit)
    private val targets = mutableListOf<Target>()

    fun register(sourceId: String, exit: () -> Unit): AutoCloseable {
        val target = Target(sourceId, exit)
        targets += target
        return AutoCloseable { targets.remove(target) }
    }

    fun exit(sourceId: String?): Boolean {
        val target = targets.lastOrNull { sourceId == null || it.sourceId == sourceId } ?: return false
        target.exit()
        return true
    }
}
