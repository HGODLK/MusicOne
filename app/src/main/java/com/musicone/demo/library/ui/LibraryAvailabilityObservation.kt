package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ObserveLibraryFileAvailability(snapshot: LibrarySnapshot, store: MusicLibraryStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val local by rememberUpdatedState(snapshot.tracks.filter { it.source == MusicSource.LOCAL })
    val mutex = remember { Mutex() }
    suspend fun refresh() = mutex.withLock {
        val changed = inspectLibraryFiles(context, local)
        if (changed.isNotEmpty()) store.updateLocalAvailability(changed)
    }
    LaunchedEffect(local.map { it.id to it.localUri }) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { refresh() } }
}
