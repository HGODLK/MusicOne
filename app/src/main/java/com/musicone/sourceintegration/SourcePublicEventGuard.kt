package com.musicone.sourceintegration

/** 包装点击回调时记下当前账号版本，换号后旧按钮的延迟点击自动失效。 */
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
