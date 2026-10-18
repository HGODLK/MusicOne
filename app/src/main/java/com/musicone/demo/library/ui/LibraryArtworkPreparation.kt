package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 添加后即准备歌单大封面，卡片和详情仍消费已有的同一份图片缓存与渐变。 */
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
