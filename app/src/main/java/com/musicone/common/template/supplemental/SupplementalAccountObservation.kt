package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.musicone.source.sdk.SourceAccountState
import com.musicone.source.sdk.SourceSessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** 新页面契约按完整会话身份失效，旧包仍只沿用原账号页的轮询。 */
@Composable
internal fun ObserveSupplementalAccount(model: SupplementalSourceModel, page: SupplementalPage) {
    val modern = model.pageProvider != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, modern, if (modern) null else page, lifecycle) {
        if (!modern) {
            if (page == SupplementalPage.ACCOUNT) while (true) {
                delay(500)
                model.refreshAccount()
            }
        } else if (model.entry.authentication != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (model.refreshAccount()) model.refreshDestinations()
                    delay(500)
                }
            }
        }
    }
}

/** revision 更新、换号和会话状态变化均使旧动作失效，摘要文本变化不重置导航。 */
internal class SupplementalAccountVersion {
    private data class Identity(val status: SourceSessionStatus?, val accountId: String?, val revision: Long)
    private var identity: Identity? = null
    private fun identity(state: SourceAccountState?) = Identity(state?.status, state?.account?.id, state?.revision ?: 0L)
    fun update(state: SourceAccountState?): Boolean {
        val next = identity(state)
        return (identity != next).also { identity = next }
    }
    fun matches(state: SourceAccountState?): Boolean = identity == identity(state)
}

internal fun cancelSupplementalAccountRequests(scope: CoroutineScope) {
    scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancel() }
}
