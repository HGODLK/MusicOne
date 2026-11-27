package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 本地歌单变化时提前预热封面缓存，卡片和详情页直接复用。 */
@Composable
internal fun PrepareLibraryArtwork(snapshot: LibrarySnapshot) {
    val context = LocalContext.current.applicationContext
    val covers = snapshot.playlists.mapNotNull { it.artworkUrl }.distinct().sorted()
    LaunchedEffect(covers) {
        withContext(Dispatchers.IO) { MusicDiskCache.get(context) }
        covers.filter { ArtworkRepository.peek(it) == null }.chunked(2).forEach { batch ->
            coroutineScope { batch.map { url -> async { ArtworkRepository.load(url) } }.awaitAll() }
        }
    }
}
