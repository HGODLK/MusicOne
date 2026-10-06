package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QqMusicFeedViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = QqMusicFeedRepository(application)
    private val mutableState = MutableStateFlow(QqMusicFeedState())
    val state = mutableState.asStateFlow()
    val content = state.map(QqMusicFeedState::toContent).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, QqMusicFeedContent())
    private var revision: Long? = null
    private var job: Job? = null
    private var artworkJob: Job? = null
    private var pager = newPager()
    private var visibleBatchSize = 9
    private var artworkSizes = QqFeedArtworkSizes()
    private var textPreparer: QqFeedTextPreparer? = null
    private var refreshExit: CompletableDeferred<Unit>? = null
    private var snapshot: QqFeedSnapshotStore? = null

    private fun newPager() = QqMusicFeedPager(::loadWithRetry)

    fun finishRefreshExit() { refreshExit?.complete(Unit) }

    fun updateViewport(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
        artwork: QqFeedArtworkSizes, text: QqFeedTextPreparer) {
        visibleBatchSize = qqMusicFeedBatchSize(width, height)
        artworkSizes = artwork
        textPreparer = text
    }

    fun configure(sessionRevision: Long) {
        if (revision == sessionRevision) return
        revision = sessionRevision
        job?.cancel()
        artworkJob?.cancel()
        mutableState.value = QqMusicFeedState(loading = true)
        refreshExit?.cancel()
        pager = newPager()
        job = viewModelScope.launch {
            val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val session = PlatformPreferences(getApplication()).readSession(MusicSource.QQ)
                val store = QqFeedSnapshotStore(MusicDiskCache.get(getApplication()), session.cacheNamespace())
                store to store.read()
            }
            if (revision != sessionRevision) return@launch
            snapshot = cached.first
            // 先整理缓存中的未展示顺序，避免网络刷新完成前仍显示同类卡片连续堆叠。
            val cachedCards = withContext(Dispatchers.Default) {
                qqMusicFeedDisplayCards(interleaveQqMusicFeedCards(cached.second))
            }
            textPreparer?.prepare(cachedCards)
            mutableState.value = QqMusicFeedState(cards = cachedCards, settled = cachedCards.isNotEmpty())
            load(replace = cached.second.isNotEmpty())
        }
    }

    fun refresh() {
        // 官方下拉刷新会打断正在进行的旧请求；否则刷新按钮在首屏加载期间会变成无效点击。
        job?.cancel()
        artworkJob?.cancel()
        job = null
        pager = newPager()
        refreshExit?.cancel()
        if (mutableState.value.loading) {
            mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
        }
        load(replace = true)
    }
    fun loadMore() = load(replace = false)
    fun retry() = load(replace = mutableState.value.refreshRequired)

    private fun load(replace: Boolean) {
        if (mutableState.value.loading) return
        val previous = mutableState.value
        val requestedRevision = revision
        val sessionPager = pager
        val exitReady = if (replace) CompletableDeferred<Unit>().also { refreshExit = it } else null
        mutableState.value = previous.copy(loading = true, message = null, refreshing = false)
        job = viewModelScope.launch {
            try {
                // 刷新先交付一屏左右的官方卡位，继续下滑时再按视口补齐，避免首批探测过多页面。
                val firstBatch = replace || previous.cards.isEmpty()
                val batchSize = if (firstBatch) {
                    QQ_FEED_REFRESH_BATCH_SIZE
                } else visibleBatchSize
                val batch = withContext(Dispatchers.Default) {
                    sessionPager.next(previous.cards, batchSize, replace)
                }
                val visibleCards = batch.visible
                if (revision != requestedRevision) return@launch
                // 先准备下一屏封面，与列表整理、退场及磁盘保存并行；新批次取消旧预载。
                val requestedArtworkSizes = artworkSizes
                artworkJob?.cancel()
                artworkJob = viewModelScope.launch(Dispatchers.IO) {
                    preloadQqFeedArtwork(visibleCards.take(QQ_FEED_REFRESH_BATCH_SIZE), requestedArtworkSizes)
                }
                val merged = withContext(Dispatchers.Default) {
                    appendQqMusicFeedDisplayCards(previous.cards, visibleCards, replace)
                }
                // 专用测量器在后台准备新卡字号，显示时只读取现有排版缓存。
                textPreparer?.prepare(visibleCards)
                if (exitReady != null) {
                    mutableState.value = mutableState.value.copy(refreshing = true)
                    // 页面暂时卸载时也能完成替换，不把请求永远挂在退场回调上。
                    kotlinx.coroutines.withTimeoutOrNull(600) { exitReady.await() }
                }
                if (revision != requestedRevision) return@launch
                mutableState.value = QqMusicFeedState(
                    cards = merged,
                    generation = previous.generation + if (replace) 1 else 0,
                    settled = true,
                )
                val store = snapshot
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store?.write(merged) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != requestedRevision) return@launch
                mutableState.value = previous.copy(loading = false, refreshing = false, settled = true, automaticLoading = false,
                    message = error.message ?: "音乐流加载失败，轻触重试", refreshRequired = replace)
            }
        }
    }

    private suspend fun loadWithRetry(
        page: Int,
        shelfCount: Int,
        shelfIds: List<String>,
        uniqueKeys: List<String>,
    ): QqMusicFeedPage {
        var failure: Exception? = null
        repeat(QQ_FEED_LOAD_ATTEMPTS) { attempt ->
            try {
                return repository.load(page, shelfCount, shelfIds, uniqueKeys)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is PlatformApiException && error.apiCode == 301) throw error
                failure = error
                if (attempt < QQ_FEED_LOAD_ATTEMPTS - 1) delay(QQ_FEED_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw failure ?: PlatformApiException("音乐流加载失败，请稍后重试")
    }
}

private const val QQ_FEED_LOAD_ATTEMPTS = 3
private const val QQ_FEED_RETRY_DELAY_MS = 600L
private const val QQ_FEED_REFRESH_BATCH_SIZE = 6
