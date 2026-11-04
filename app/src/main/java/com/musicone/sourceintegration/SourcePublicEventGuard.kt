package com.musicone.sourceintegration

/** 每次组合创建事件时捕获账号版本；旧事件失效，新组合无需重建整个组件作用域。 */
internal class SourcePublicEventGuard(private val checkAccess: () -> Unit, private val accountVersion: () -> Any?) {
    fun event(action: () -> Unit): () -> Unit {
        val expected = accountVersion()
        return { checkAccess(); if (accountVersion() == expected) action() }
    }

    fun <T> valueEvent(action: (T) -> Unit): (T) -> Unit {
        val expected = accountVersion()
        return { value -> checkAccess(); if (accountVersion() == expected) action(value) }
    }
}
