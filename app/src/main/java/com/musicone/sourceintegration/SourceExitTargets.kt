package com.musicone.sourceintegration

/** 主线程维护当前打开的音源外部页面栈，点退出时只关掉同一个音源最顶上的那一层。 */
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
