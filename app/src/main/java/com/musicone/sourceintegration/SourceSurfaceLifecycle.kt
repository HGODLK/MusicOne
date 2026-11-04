package com.musicone.sourceintegration

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** 被宿主覆盖的包仍保留画面，但不再以 STARTED 身份注册返回处理。 */
internal class SourceSurfaceLifecycle(private val parent: LifecycleOwner) : LifecycleOwner, AutoCloseable {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var interactive = false
    private val observer = LifecycleEventObserver { _, _ -> update() }

    init { parent.lifecycle.addObserver(observer) }

    fun setInteractive(value: Boolean) {
        interactive = value
        update()
    }

    private fun update() {
        val parentState = parent.lifecycle.currentState
        registry.currentState = if (!interactive && parentState.isAtLeast(Lifecycle.State.STARTED)) {
            Lifecycle.State.CREATED
        } else parentState
    }

    override fun close() {
        parent.lifecycle.removeObserver(observer)
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
