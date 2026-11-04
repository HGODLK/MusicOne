package com.musicone.sourceintegration

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.musicone.*

/** 模板内部继续使用原有 AndroidViewModel，但不借用宿主根页的实例和选择状态。 */
private class SourcePublicPageStore(application: Application, source: MusicSource) :
    ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    private val androidFactory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass == PlatformSettingsViewModel::class.java) {
                @Suppress("UNCHECKED_CAST")
                return PlatformSettingsViewModel(application, source) as T
            }
            return androidFactory.create(modelClass)
        }
    }
}

@Composable
internal fun SourcePublicPageState(source: MusicSource, instanceToken: Any?, content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val store = remember(source, instanceToken) { SourcePublicPageStore(application, source) }
    DisposableEffect(store) { onDispose { store.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides store, content = content)
}
