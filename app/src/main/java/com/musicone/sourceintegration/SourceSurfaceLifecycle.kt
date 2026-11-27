package com.musicone.sourceintegration

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** 宿主把音源包页面盖住时把它的生命周期降到 CREATED，保留画面但停掉它的返回键拦截。 */
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
