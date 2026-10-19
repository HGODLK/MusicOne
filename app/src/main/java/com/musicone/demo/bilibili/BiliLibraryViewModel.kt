package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 账号代次与各类任务序号分开，分页、解析和导入互不作废。 */
internal class BiliLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val api = BiliApi(application)
    private val folderCache = BiliFolderCache(application)
    val folderRepository = BiliFolderRepository(folderCache) { account, folder, page, query ->
        api.getFavoriteVideos(folder, page, account.cookie, query)
    }
    val folderDirectory = BiliFolderDirectory(folderCache, viewModelScope,
        { account -> api.getFavoriteFolders(account.mid.toLong(), account.cookie) },
        { account, folder -> latestBiliFolderCover(folderRepository.first(account,
            folderRepository.entry(account.mid, folder), refresh = true).videos).orEmpty() })
    private val sessionStore = BiliSessionStore(application)
    private val library = MusicLibraryStore.get(application)
    private val syncer = BiliSubscriptionSyncer(api, library)
    private val mutable = MutableStateFlow(BiliLibraryUiState(account = sessionStore.read()).let {
        it.copy(page = it.visiblePage())
    })
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    private enum class Work { LINK, IMPORT, SUBSCRIPTION, PLAYBACK }
    private val jobs = mutableMapOf<Work, Job>()
    private val tokens = java.util.concurrent.ConcurrentHashMap<Work, Long>()
    private val active = mutableSetOf<Work>()
    private val login = BiliLoginCoordinator(api, sessionStore, mutable, viewModelScope) {
        viewModelScope.launch {
            invalidateTasks()
            folderRepository.clear()
            mutable.update { it.copy(page = BiliLibraryPage.HOME, phone = "", smsCode = "", captchaKey = "", qrCode = null) }
            folderDirectory.attach(state.value.account)
        }
    }

    init {
        viewModelScope.launch { library.state.collect { snapshot -> mutable.update { it.copy(subscriptions = snapshot.subscriptions) } } }
    }

    fun onPageEnter() {
        mutable.update { it.copy(account = sessionStore.read()).let { current -> current.copy(page = current.visiblePage()) } }
        folderDirectory.attach(state.value.account)
    }
    fun onPageExit() {
        invalidateTasks()
        folderDirectory.cancel()
        folderRepository.clear()
        login.cancelOnExit()
        mutable.update { it.copy(phone = "", smsCode = "", captchaKey = "", geetestChallenge = null) }
    }
    private fun invalidateTasks() {
        generation++
        jobs.values.forEach { it.cancel() }; jobs.clear(); active.clear(); tokens.clear()
        login.cancelAllJobs()
        mutable.update { it.copy(generation = generation, sessionRevision = generation, busy = false, working = false,
            resolvingLink = false, isSubscribing = false, playingFolderId = null) }
    }
    private fun loading() { mutable.update { it.copy(busy = active.isNotEmpty(), working = active.isNotEmpty(),
        resolvingLink = Work.LINK in active, isSubscribing = Work.SUBSCRIPTION in active,
        playingFolderId = it.playingFolderId.takeIf { Work.PLAYBACK in active }) } }
    private fun <T> task(kind: Work, work: suspend (() -> Boolean) -> T, complete: (T) -> Unit) {
        val epoch = generation
        val token = (tokens[kind] ?: 0L) + 1
        tokens[kind] = token
        jobs.remove(kind)?.cancel()
        active += kind; loading(); mutable.update { it.copy(error = null) }
        jobs[kind] = viewModelScope.launch {
            fun current() = generation == epoch && tokens[kind] == token
            try {
                val result = withContext(Dispatchers.IO) { work { !current() } }
                if (current()) complete(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current()) mutable.update { it.copy(error = error.message ?: "操作失败，请重试") } }
            finally { if (current()) { active -= kind; jobs.remove(kind); loading() } }
        }
    }

    fun switchLoginMethod(method: PlatformLoginMethod) {
        onPageExit()
        mutable.update { it.copy(loginMethod = method, message = null, error = null) }
    }
    fun startQrLogin() { invalidateTasks(); login.startQrLogin(generation) { it == generation } }
    fun cancelQrLogin() { invalidateTasks(); login.cancelQrLogin() }
    fun updatePhone(value: String) {
        if (value == state.value.phone) return
        invalidateTasks(); login.cancelOnExit()
        mutable.update { it.copy(phone = value.filter(Char::isDigit).take(20), smsCode = "", captchaKey = "", smsSent = false, error = null) }
    }
    fun updateSmsCode(value: String) { mutable.update { it.copy(smsCode = value.filter(Char::isDigit).take(10), error = null) } }
    fun sendSms() {
        val s = state.value
        if (s.busy || s.geetestChallenge != null || s.resendSeconds > 0) return
        sendSmsRequest(null)
    }
    private fun sendSmsRequest(geetest: BiliGeetestResult?) {
        val s = state.value
        if (s.phone.isBlank() || (s.resendSeconds > 0 && geetest == null)) return
        invalidateTasks()
        login.sendSms(s.countryCode, s.phone, geetest, generation) { it == generation }
    }
    private fun isCurrentChallenge(challenge: BiliGeetestChallenge): Boolean =
        state.value.geetestChallenge === challenge && challenge.requestGeneration == generation &&
            state.value.page == BiliLibraryPage.ACCOUNT && state.value.loginMethod == PlatformLoginMethod.SMS

    fun onGeetestResolved(challenge: BiliGeetestChallenge, result: BiliGeetestResult) {
        if (!isCurrentChallenge(challenge)) return
        if (!result.isComplete() || challenge.token.isBlank()) {
            failGeetest(challenge, BiliGeetestFailure.RESULT)
            return
        }
        BiliAuthDiagnostics.event("verification_success")
        sendSmsRequest(result.copy(token = challenge.token))
    }
    fun failGeetest(challenge: BiliGeetestChallenge, failure: BiliGeetestFailure) {
        if (!isCurrentChallenge(challenge)) return
        BiliAuthDiagnostics.event("verification_failed_${failure.name}")
        mutable.update { it.copy(geetestChallenge = null, smsPhase = BiliSmsPhase.IDLE,
            busy = false, working = false, message = null, error = failure.message) }
    }
    fun dismissGeetest(challenge: BiliGeetestChallenge) {
        if (!isCurrentChallenge(challenge)) return
        BiliAuthDiagnostics.event("verification_cancelled")
        mutable.update { it.copy(geetestChallenge = null, smsPhase = BiliSmsPhase.IDLE,
            message = "已取消安全验证，可重新获取验证码", error = null) }
    }
    fun loginBySms() {
        val s = state.value
        if (s.busy || s.geetestChallenge != null) return
        if (s.phone.isBlank() || s.smsCode.isBlank() || s.captchaKey.isBlank()) {
            mutable.update { it.copy(error = "请先发送并输入短信验证码") }; return
        }
        invalidateTasks()
        login.loginBySms(s.countryCode, s.phone, s.smsCode, s.captchaKey, generation) { it == generation }
    }
    fun logout() { invalidateTasks(); folderDirectory.attach(null); folderRepository.clear(); login.logout(); mutable.update {
        BiliLibraryUiState(page = BiliLibraryPage.ACCOUNT, generation = generation, sessionRevision = generation)
    } }

    fun openPage(page: BiliLibraryPage) { mutable.update {
        it.copy(page = if (it.account == null) BiliLibraryPage.ACCOUNT else page, error = null, message = null)
    } }
    fun backPage(): Boolean {
        val current = state.value
        if (current.account == null || current.page == BiliLibraryPage.HOME) return false
        if (current.page == BiliLibraryPage.ACCOUNT) onPageExit()
        if (current.page == BiliLibraryPage.VIDEO) {
            jobs.remove(Work.LINK)?.cancel(); tokens[Work.LINK] = (tokens[Work.LINK] ?: 0L) + 1
            active -= Work.LINK; loading()
        }
        openPage(when (current.page) {
            BiliLibraryPage.VIDEO -> current.videoReturnPage
            BiliLibraryPage.FOLDER -> current.folderReturnPage
            else -> BiliLibraryPage.HOME
        })
        return true
    }

    fun loadFolders() {
        folderDirectory.refresh()
    }

    fun loadFolderCover(folder: BiliFavFolder) = folderDirectory.cover(folder)

    fun updateFolderCover(account: BiliAccountSession, folder: Long, videos: List<BiliVideoItem>) {
        if (state.value.account == account) folderDirectory.updateCover(account.mid, folder, latestBiliFolderCover(videos))
    }

    fun playFolder(folder: BiliFavFolder, onPlay: (MusicPlaylist) -> Unit) {
        val account = state.value.account ?: return
        mutable.update { it.copy(playingFolderId = folder.id) }
        // 收藏夹缓存与浏览共用分页，已完成的页不因播放再次请求。
        task(Work.PLAYBACK, {
            val content = folderRepository.complete(account, folderRepository.entry(account.mid, folder.id))
            content.asPlaylist(folder)
        }, onPlay)
    }
    fun selectFolder(folder: BiliFavFolder) {
        mutable.update { it.copy(page = BiliLibraryPage.FOLDER, selectedFolder = folder,
            folderReturnPage = it.page, error = null, message = null) }
    }
    fun updateLinkInput(value: String) { mutable.update { it.copy(linkInput = value, error = null) } }
    fun resolveLink(text: String = state.value.linkInput) {
        val current = state.value
        val cookie = current.account?.cookie.orEmpty()
        mutable.update { it.copy(page = BiliLibraryPage.VIDEO,
            videoReturnPage = if (current.page == BiliLibraryPage.FOLDER) BiliLibraryPage.FOLDER else BiliLibraryPage.LINK,
            currentVideoDetails = null, videoPages = emptyList(), selectedCids = emptySet()) }
        task(Work.LINK, {
            val expanded = BiliTrackMapper.expandLink(text)
            val bvid = BiliTrackMapper.resolveBvid(expanded) ?: error("未识别到有效 BV 号或B站链接")
            val details = api.getVideoDetails(bvid, cookie)
            val pages = details.pages.ifEmpty { api.getVideoPages(bvid, cookie) }
            require(pages.isNotEmpty()) { "未找到可导入的分 P" }
            val number = BiliTrackMapper.resolvePageNumber(expanded)
            val selected = if (number == null) pages.mapTo(hashSetOf()) { it.cid } else setOf(
                pages.firstOrNull { it.page == number }?.cid ?: error("链接指定的分 P 已不存在"))
            Triple(details.copy(pages = pages), pages, selected)
        }) { (details, pages, selected) ->
            mutable.update { it.copy(currentVideoDetails = details, currentVideoBvid = details.bvid, currentVideoTitle = details.title,
                videoPages = pages, selectedCids = selected) }
        }
    }
    fun toggleCidSelection(cid: Long) { mutable.update { it.copy(selectedCids = if (cid in it.selectedCids) it.selectedCids - cid else it.selectedCids + cid) } }
    fun selectAllCids() { mutable.update { it.copy(selectedCids = it.videoPages.mapTo(hashSetOf()) { part -> part.cid }) } }
    fun clearCidSelection() { mutable.update { it.copy(selectedCids = emptySet()) } }
    fun setTargetPlaylist(id: String, title: String) { mutable.update { it.copy(targetPlaylistId = id, targetPlaylistTitle = title) } }
    fun importSelectedParts(targetPlaylistId: String) {
        val s = state.value
        val details = s.currentVideoDetails ?: return
        val parts = s.videoPages.filter { it.cid in s.selectedCids }
        if (parts.isEmpty()) return
        val video = BiliVideoItem(details.aid, details.bvid, parts.first().cid, details.title, details.cover, details.upName, details.durationSec)
        val tracks = parts.map { BiliTrackMapper.toTrack(video, it, s.videoPages.size > 1) }
        task(Work.IMPORT, { cancelled ->
            if (cancelled()) throw CancellationException()
            library.addTracks(targetPlaylistId, tracks)
        }) { mutable.update { it.copy(message = "已添加 ${tracks.size} 首歌曲到${s.targetPlaylistTitle}") } }
    }
    fun subscribeFolder(folder: BiliFavFolder, targetPlaylistId: String? = null) {
        val account = state.value.account ?: return
        task(Work.SUBSCRIPTION, { cancelled ->
            val existing = library.state.value.subscriptions.firstOrNull { it.folderId == folder.id && it.ownerId == account.mid }
            var createdId: String? = null
            var committed = false
            try {
                val target = existing?.playlistId ?: library.createPlaylist(folder.title.ifBlank { "B站收藏夹" }) { createdId = it }
                syncer.syncFolder(folder, target, account, cancelled, onCommitted = { committed = true })
            } catch (error: Exception) {
                if (!committed) createdId?.let { withContext(kotlinx.coroutines.NonCancellable) { library.deletePlaylist(it) } }
                throw error
            }
        }) { count -> mutable.update { it.copy(message = "订阅已同步，共 $count 首歌曲") } }
    }
    fun refreshSubscription(playlistId: String) {
        val account = state.value.account ?: return
        val sub = library.state.value.subscriptions.firstOrNull { it.playlistId == playlistId } ?: return
        if (sub.ownerId != account.mid) { mutable.update { it.copy(error = "请登录订阅所属的B站账号") }; return }
        task(Work.SUBSCRIPTION, { cancelled -> syncer.syncSubscription(playlistId, account, cancelled) }) { count ->
            mutable.update { it.copy(message = "订阅已刷新，共 $count 首歌曲") }
        }
    }
    suspend fun syncSubscription(playlistId: String): Int {
        val epoch = generation
        val account = sessionStore.read() ?: error("请登录B站账号")
        return syncer.syncSubscription(playlistId, account) { generation != epoch || sessionStore.read()?.mid != account.mid }
    }
    fun stopSubscription(playlistId: String) {
        task(Work.SUBSCRIPTION, { library.stopSubscription(playlistId) }) { mutable.update { it.copy(message = "已停止订阅，当前内容保留") } }
    }
}
