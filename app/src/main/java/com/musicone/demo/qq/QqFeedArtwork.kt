package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil

internal data class QqFeedArtworkSizes(val card: Int = 768, val thumbnail: Int = 768)
internal val LocalQqFeedArtworkSizes = staticCompositionLocalOf { QqFeedArtworkSizes() }
private data class FeedArtworkRequest(val url: String, val maxSide: Int)
private val feedArtworkPreloadLimit = Semaphore(3)

/** 按真实像素向上取整，预载与卡片显示使用同一尺寸缓存键。 */
internal fun qqFeedArtworkSizes(width: Dp, height: Dp, density: Float): QqFeedArtworkSizes {
    val contentMax = qqMusicFeedContentMaxWidth(width, height)
    val gutter = if (width >= 600.dp) maxOf(32.dp, (width - contentMax) / 2) else 20.dp
    val columns = qqMusicFeedColumns(width, height)
    val cardWidth = (width - gutter * 2 - 14.dp * (columns - 1)) / columns
    fun pixels(dp: Float) = (ceil(dp * density / 64f).toInt() * 64).coerceIn(64, 768)
    return QqFeedArtworkSizes(pixels(cardWidth.value), pixels(62f))
}

internal suspend fun preloadQqFeedArtwork(cards: List<QqMusicFeedCard>,
    sizes: QqFeedArtworkSizes) = coroutineScope {
    val requests = cards.flatMap { card ->
        when (card) {
            is QqMusicFeedCard.Playlist -> listOfNotNull(card.playlist.artworkUrl).map { FeedArtworkRequest(it, sizes.card) }
            is QqMusicFeedCard.Song -> listOfNotNull(card.track.artworkUrl).map { FeedArtworkRequest(it, sizes.card) }
            is QqMusicFeedCard.SongGroup -> card.tracks.mapNotNull { it.artworkUrl }.map { FeedArtworkRequest(it, sizes.thumbnail) }
            // 横向货架先准备第一组，后续页面由现有 Pager 按需加载。
            is QqMusicFeedCard.SongShelf -> card.pages.firstOrNull().orEmpty().mapNotNull { it.track.artworkUrl }
                .map { FeedArtworkRequest(it, sizes.thumbnail) }
        }
    }.distinct().filter { ArtworkRepository.peek(it.url, it.maxSide) == null }
    val pending = requests.map { request ->
        async(Dispatchers.IO) {
            feedArtworkPreloadLimit.withPermit {
                try {
                    // 提前准备纹理，避免卡片首次绘制时才集中上传；复用显示端的尺寸缓存。
                    ArtworkRepository.load(request.url, maxSide = request.maxSide)?.prepareToDraw()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 单张封面失败交由可见卡片重试，不影响已发布的信息流。
                }
            }
        }
    }
    // 调用方持有独立预载任务，发布卡片无需等待，刷新或新批次可以取消所有子任务。
    pending.awaitAll()
}
