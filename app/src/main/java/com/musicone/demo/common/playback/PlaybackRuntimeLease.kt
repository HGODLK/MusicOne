package com.musicone.demo

/** 页面和服务共用同一实例，最后一个持有者离开后才清理任务。仅在主线程使用。 */
internal class PlaybackRuntimeLease<T>(private val create: () -> T, private val dispose: (T) -> Unit) {
    private val owners = mutableSetOf<Any>()
    private var value: T? = null

    fun acquire(owner: Any): T {
        owners.add(owner)
        return value ?: create().also { value = it }
    }

    fun release(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val previous = value ?: return
        value = null
        dispose(previous)
    }
}
