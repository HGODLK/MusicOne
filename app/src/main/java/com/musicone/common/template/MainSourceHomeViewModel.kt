package com.musicone

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

internal class MainSourceHomeViewModel(application: Application) : AndroidViewModel(application) {
    private var configuredSource = PlatformPreferences(application).selectedSource()
    private val mutableState = MutableStateFlow(MainSourceHomeState())
    val state = mutableState.asStateFlow()
    val content = state.map(MainSourceHomeState::toContent).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainSourceHomeContent())
    val refreshLoading = state.map { it.refreshLoading }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var revision: Long? = null
    private var job: Job? = null
    private var artworkJob: Job? = null
    private var pager = newPager()
    private var visibleBatchSize = 9
    private var artworkSizes = MainSourceFeedArtworkSizes()
    private var textPreparer: MainSourceFeedTextPreparer? = null
    private var refreshExit: CompletableDeferred<Unit>? = null
    private var snapshot: MainSourceFeedSnapshotStore? = null
    private var refreshedDay: String? = null

    private fun newPager() = MainSourceHomePager(getApplication(), configuredSource)

    fun finishRefreshExit() { refreshExit?.complete(Unit) }

    fun updateViewport(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
        artwork: MainSourceFeedArtworkSizes, text: MainSourceFeedTextPreparer) {
        visibleBatchSize = mainSourceHomeBatchSize(width, height)
        artworkSizes = artwork
        textPreparer = text
    }

    fun configure(sessionRevision: Long, source: MusicSource = PlatformPreferences(getApplication()).selectedSource()) {
        if (revision == sessionRevision && configuredSource == source) return
        configuredSource = source
        revision = sessionRevision
        snapshot = null
        refreshedDay = null
        job?.cancel()
        artworkJob?.cancel()
        mutableState.value = MainSourceHomeState(loading = true, refreshLoading = true)
        refreshExit?.cancel()
        pager = newPager()
        job = viewModelScope.launch {
            val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val session = PlatformPreferences(getApplication()).readSession(configuredSource)
                val store = MainSourceFeedSnapshotStore(MusicDiskCache.get(getApplication()), session.cacheNamespace())
                store to store.read()
            }
            if (revision != sessionRevision) return@launch
            snapshot = cached.first
            refreshedDay = cached.first.refreshedDay
            // 先整理缓存中的未展示顺序，避免网络刷新完成前仍显示同类卡片连续堆叠。
            val cachedCards = withContext(Dispatchers.Default) {
                mainSourceHomeDisplayCards(interleaveMainSourceHomeCards(cached.second))
            }
            textPreparer?.prepare(cachedCards)
            preloadMainSourceFeedArtwork(cachedCards.take(visibleBatchSize), artworkSizes, getApplication())
            if (revision != sessionRevision || configuredSource != source) return@launch
            mutableState.value = MainSourceHomeState(cards = cachedCards, settled = cachedCards.isNotEmpty())
            if (homeDailyRefreshDue(false, cachedCards.isNotEmpty(), refreshedDay, homeRefreshDay())) {
                load(replace = cachedCards.isNotEmpty())
            }
        }
    }

    fun refresh(force: Boolean = true) {
        if (snapshot == null || (!force && (mutableState.value.loading ||
                !homeDailyRefreshDue(false, mutableState.value.cards.isNotEmpty(), refreshedDay, homeRefreshDay())))) return
        // 官方下拉刷新会打断正在进行的旧请求；否则刷新按钮在首屏加载期间会变成无效点击。
        job?.cancel()
        artworkJob?.cancel()
        job = null
        pager = newPager()
        refreshExit?.cancel()
        if (mutableState.value.loading) {
            mutableState.value = mutableState.value.copy(loading = false, refreshing = false, refreshLoading = false)
        }
        load(replace = true)
    }
    fun loadMore() = load(replace = false)
    fun retry() = load(replace = mutableState.value.refreshRequired)

    private fun load(replace: Boolean) {
        if (mutableState.value.loading || (!replace && pager.finished)) return
        val previous = mutableState.value
        val requestedRevision = revision
        val requestedDay = homeRefreshDay()
        val startsDailyFeed = replace || previous.cards.isEmpty()
        val store = snapshot
        val sessionPager = pager
        val exitReady = if (replace) CompletableDeferred<Unit>().also { refreshExit = it } else null
        mutableState.value = previous.copy(loading = true, message = null, refreshing = false, refreshLoading = startsDailyFeed)
        job = viewModelScope.launch {
            try {
                // 刷新先交付一屏左右的官方卡位，继续下滑时再按视口补齐，避免首批探测过多页面。
                val firstBatch = replace || previous.cards.isEmpty()
                val batchSize = if (firstBatch) {
                    MAIN_SOURCE_FEED_REFRESH_BATCH_SIZE
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
                    preloadMainSourceFeedArtwork(visibleCards.take(MAIN_SOURCE_FEED_REFRESH_BATCH_SIZE), requestedArtworkSizes)
                }
                val merged = withContext(Dispatchers.Default) {
                    appendMainSourceHomeDisplayCards(previous.cards, visibleCards, replace)
                }
                // 专用测量器在后台准备新卡字号，显示时只读取现有排版缓存。
                textPreparer?.prepare(visibleCards)
                if (exitReady != null) {
                    mutableState.value = mutableState.value.copy(refreshing = true)
                    // 页面暂时卸载时也能完成替换，不把请求永远挂在退场回调上。
                    kotlinx.coroutines.withTimeoutOrNull(600) { exitReady.await() }
                }
                if (revision != requestedRevision) return@launch
                mutableState.value = MainSourceHomeState(
                    cards = merged,
                    generation = previous.generation + if (replace) 1 else 0,
                    settled = true,
                )
                // 分页只追加内容，不把昨日列表续期为今日；只有成功首批或整页刷新更新日期。
                if (startsDailyFeed) refreshedDay = requestedDay
                withContext(Dispatchers.IO) { store?.write(merged, refreshedDay) }
                // 已显示三条推荐后，用现有追加流程准备暂存音乐流，不延长首页刷新状态。
                if (firstBatch && sessionPager.hasBufferedItems && pager === sessionPager && revision == requestedRevision) {
                    load(replace = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != requestedRevision) return@launch
                mutableState.value = previous.copy(loading = false, refreshing = false, settled = true, automaticLoading = false,
                    message = error.message ?: "音乐流加载失败，轻触重试", refreshRequired = replace, refreshLoading = false)
            }
        }
    }

}
private const val MAIN_SOURCE_FEED_REFRESH_BATCH_SIZE = 6
