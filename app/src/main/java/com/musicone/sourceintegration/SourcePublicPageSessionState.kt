package com.musicone.sourceintegration

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.musicone.MainSourceLibraryViewModel
import com.musicone.MusicSource
import com.musicone.PlatformPreferences
import com.musicone.source.sdk.SourceSessionStatus

/** 只按运行实例与登录边界保留资料库，临时导航仍归页面自己的 Store 管理。 */
internal class SourcePublicPageScopes<T>(private val create: (String, String) -> T, private val discard: (T) -> Unit) {
    private data class Entry<T>(val instance: Any?, val account: String, val value: T)
    private val entries = mutableMapOf<String, Entry<T>>()
    private var serial = 0L

    fun obtain(sourceId: String, instance: Any?, account: String): T {
        val existing = entries[sourceId]
        if (existing != null && existing.instance === instance && existing.account == account) return existing.value
        entries.remove(sourceId)?.let { discard(it.value) }
        return create(sourceId, "public-my:${sourceId}:${++serial}").also {
            entries[sourceId] = Entry(instance, account, it)
        }
    }

    fun reconcile(current: (String) -> Pair<Any?, String>?) {
        entries.keys.toList().forEach { sourceId ->
            val entry = entries.getValue(sourceId)
            val boundary = current(sourceId)
            if (boundary == null || boundary.first !== entry.instance || boundary.second != entry.account) {
                entries.remove(sourceId)
                discard(entry.value)
            }
        }
    }

    fun values(): List<T> = entries.values.map { it.value }
    fun clear() { entries.values.forEach { discard(it.value) }; entries.clear() }
}

private class LibraryPageScope(val source: MusicSource, val savedKey: String) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var library: MainSourceLibraryViewModel? = null
    var closed = false
}

private class PublicPageSessionKeeper(
    private val application: Application,
    val savedState: SaveableStateHolder,
) {
    private val controller = SourcePackageController.get(application)
    private val preferences = PlatformPreferences(application)
    private val scopes = SourcePublicPageScopes(
        create = { sourceId, key -> LibraryPageScope(MusicSource.fromId(sourceId), key) },
        discard = { scope ->
            scope.closed = true
            scope.viewModelStore.clear()
            savedState.removeState(scope.savedKey)
        },
    )

    private fun accountBoundary(source: MusicSource): String {
        val state = controller.running(source.id)?.entry?.authentication?.currentAccount()
        if (state != null) {
            val account = state.account
            return if (state.status == SourceSessionStatus.AUTHENTICATED && account != null)
                "account:${account.id}" else "guest:${state.status}"
        }
        val session = preferences.readSession(source)
        return if (session.credential.isNotBlank()) "account:${session.account?.userId.orEmpty()}" else "guest"
    }

    fun scope(source: MusicSource, instance: Any?): LibraryPageScope {
        return scopes.obtain(source.id, instance, accountBoundary(source))
    }

    fun library(scope: LibraryPageScope): MainSourceLibraryViewModel {
        val library = scope.library ?: ViewModelProvider(scope,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainSourceLibraryViewModel::class.java]
            .also { scope.library = it }
        configure(scope)
        return library
    }

    private fun configure(scope: LibraryPageScope) {
        val revision = controller.running(scope.source.id)?.entry?.authentication?.currentAccount()?.revision
            ?: 0L
        scope.library?.configure(scope.source, revision)
    }

    fun reconcile() {
        scopes.reconcile { sourceId ->
            val source = MusicSource.fromId(sourceId)
            val instance = controller.running(sourceId)
            if (instance == null && source != MusicSource.LOCAL) null
            else instance to accountBoundary(source)
        }
        // 页面未挂载时也取消同账号旧会话的请求，已有目录仍留在原作用域中。
        scopes.values().forEach(::configure)
    }

    fun close() = scopes.clear()
}

private data class PublicPageSessionHost(val keeper: PublicPageSessionKeeper, val revision: Long)
private val LocalPublicPageSessionHost = staticCompositionLocalOf<PublicPageSessionHost?> { null }

@Composable
internal fun SourcePublicPageSessionHost(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val savedState = rememberSaveableStateHolder()
    val keeper = remember(application, savedState) { PublicPageSessionKeeper(application, savedState) }
    val accountRevision by SourceApplicationHost.get(application).accountRevision.collectAsState()
    val installed by SourcePackageController.get(application).installed.collectAsState()
    val host = remember(keeper, accountRevision, installed) { PublicPageSessionHost(keeper, accountRevision) }
    SideEffect { keeper.reconcile() }
    DisposableEffect(keeper) { onDispose { keeper.close() } }
    CompositionLocalProvider(LocalPublicPageSessionHost provides host, content = content)
}

@Composable
internal fun rememberSourcePublicLibraryViewModel(
    source: MusicSource,
    instanceToken: Any?,
): MainSourceLibraryViewModel {
    val host = checkNotNull(LocalPublicPageSessionHost.current) { "公共页面未绑定资料库会话宿主" }
    val scope = host.keeper.scope(source, instanceToken)
    return host.keeper.library(scope)
}

@Composable
internal fun SourcePublicMyPageState(source: MusicSource, instanceToken: Any?, content: @Composable () -> Unit) {
    // 弹出的 My 与调用者可以同时挂载，只让嵌入根页占用跨返回的保存槽位。
    if (LocalSourcePublicSurface.current?.separateRoot != true) {
        content()
        return
    }
    val host = checkNotNull(LocalPublicPageSessionHost.current) { "公共页面未绑定资料库会话宿主" }
    val scope = host.keeper.scope(source, instanceToken)
    host.keeper.savedState.SaveableStateProvider(scope.savedKey, content)
    DisposableEffect(scope) {
        onDispose { if (scope.closed) host.keeper.savedState.removeState(scope.savedKey) }
    }
}
