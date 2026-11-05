package com.musicone.source.netease

import android.content.Context
import androidx.compose.runtime.*
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val NETEASE_SOURCE_ID = "com.musicone.source.netease"
internal const val NETEASE_PAGE_COLOR = 0xFFE84F50L
internal val NeteaseMusicThemeColor = androidx.compose.ui.graphics.Color(NETEASE_PAGE_COLOR)

internal class NeteasePackageState(val environment: SourceEnvironment) : AutoCloseable {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val registration = environment.host.registerTask { close() }
    private val preferences = environment.context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    val collectionSort = NeteaseCollectionSort(environment.context)
    val revision = NeteaseAccountRevision()
    var dataRevision by mutableLongStateOf(0L)
        private set
    val loginGeneration = AtomicLong(0)
    @Volatile private var closed = false
    @Volatile var expired = false
    val api = NeteaseApiClient { deviceId() }
    var account by mutableStateOf(readAccount())
    private val mutablePageState = MutableStateFlow(SourcePageState())
    val pageState = mutablePageState.asStateFlow()
    val navigation = NeteasePageNavigation { value ->
        mutablePageState.value = SourcePageState(value,
            when (value) { "playlist" -> selectedPlaylist?.title ?: "歌单"; "authentication" -> "登录网易云音乐"; "settings" -> "网易云音乐设置"; else -> "网易云音乐" },
            value in setOf("home", "search", "my"))
    }
    var page: String
        get() = navigation.page
        set(value) { navigation.replace(value) }
    var bottomInset by mutableStateOf(80f)
    var message by mutableStateOf<String?>(null)
    val homePlaylists = NeteasePageLoad<List<NeteasePlaylist>>(emptyList())
    val homeTracks = NeteasePageLoad<List<NeteaseTrack>>(emptyList())
    private val library = NeteasePageLoad<List<NeteasePlaylist>>(emptyList())
    val recommendations get() = homePlaylists.value
    val recommendedTracks get() = homeTracks.value
    val catalog get() = NeteaseCatalogState(library.value, library.loading, library.error)
    var selectedPlaylist by mutableStateOf<NeteasePlaylist?>(null)
    var playlistPage by mutableStateOf<NeteaseTrackPage?>(null)
    var searchQuery by mutableStateOf("")
    var searchTracks by mutableStateOf<List<NeteaseTrack>>(emptyList())
    var loading by mutableStateOf(false)
    private var homePage = 0
    private var searchJob: Job? = null

    fun check(expected: Long = revision.get()) { if (closed || revision.get() != expected) throw CancellationException("网易云任务已失效") }
    fun credential(): String = preferences.getString("session_netease_credential", "").orEmpty()
    fun deviceId(): String = preferences.getString("session_netease_device_id", "").orEmpty().ifBlank {
        NeteaseCrypto.randomDeviceId().also { check(preferences.edit().putString("session_netease_device_id", it).commit()) }
    }
    fun currentAccount() = SourceAccountState(if (expired) SourceSessionStatus.EXPIRED else if (account != null && credential().isNotBlank())
        SourceSessionStatus.AUTHENTICATED else SourceSessionStatus.GUEST, account?.toSourceAccount(), revision.get())
    private fun readAccount(): NeteaseAccount? {
        val id = preferences.getString("session_netease_user_id", "").orEmpty()
        if (id.isBlank() || credential().isBlank()) return null
        return NeteaseAccount(userId = id, nickname = preferences.getString("session_netease_nickname", "").orEmpty(),
            avatarUrl = preferences.getString("session_netease_avatar_url", null), hasVipAccess = preferences.getBoolean("session_netease_vip_access", false),
            backgroundUrl = preferences.getString("session_netease_background_url", null), signature = preferences.getString("session_netease_signature", "").orEmpty(),
            follows = preferences.getInt("session_netease_follows", 0), followers = preferences.getInt("session_netease_followers", 0))
    }
    @Synchronized fun saveSession(cookie: String, value: NeteaseAccount, expected: Long, login: Long? = null, refreshing: Boolean = false) {
        check(expected); if (login != null) check(loginGeneration.get() == login) { "登录已取消" }
        require(neteaseHasLoginCookie(cookie)) { "缺少完整网易云登录凭证" }
        check(preferences.edit().putString("session_netease_credential", cookie).putString("session_netease_user_id", value.userId)
            .putString("session_netease_nickname", value.nickname).putString("session_netease_avatar_url", value.avatarUrl)
            .putBoolean("session_netease_vip_access", value.hasVipAccess).putString("session_netease_background_url", value.backgroundUrl)
            .putString("session_netease_signature", value.signature).putInt("session_netease_follows", value.follows)
            .putInt("session_netease_followers", value.followers).commit()) { "会话写入失败" }
        revision.saved(account?.userId, value.userId, refreshing, expired)
        account = value; expired = false
        if (revision.get() != expected) resetPageData()
    }
    @Synchronized fun logout() {
        check(); loginGeneration.incrementAndGet(); revision.incrementAndGet()
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("session_netease_") && it != "session_netease_device_id" }.forEach(editor::remove)
        // 空凭证保留用户已退出的选择，不能再次合并旧版账号。
        check(editor.putString("session_netease_credential", "").commit())
        account = null; expired = false; resetPageData(); primary("my")
    }
    fun reloadSession() { account = readAccount(); revision.incrementAndGet(); resetPageData() }
    private fun resetPageData() {
        homePlaylists.clear(); homeTracks.clear(); library.clear(); homePage = 0
        searchTracks = emptyList(); selectedPlaylist = null; playlistPage = null
        dataRevision = revision.get()
    }
    fun open(destination: String) { navigation.open(destination); message = null }
    fun back(): Boolean {
        if (!navigation.back()) return false
        message = null; return true
    }
    fun primary(destination: String) { navigation.primary(destination); message = null }
    fun action(block: suspend () -> Unit): Job = scope.launch {
        try { check(); block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (!closed) message = error.message ?: "网易云请求失败，请重试" }
    }
    fun refreshHome(next: Boolean = false) {
        check()
        val expected = revision.get(); val cookie = credential(); val vip = account?.hasVipAccess == true
        if (next && (homePlaylists.loading || homeTracks.loading)) return
        if (next) homePage++
        val page = homePage
        homePlaylists.start(scope, expected, revision::get) {
            withContext(Dispatchers.IO) { api.recommendedPlaylists(cookie, page = page) }
        }
        homeTracks.start(scope, expected, revision::get) {
            withContext(Dispatchers.IO) { api.recommendedTracks(cookie, page, hasVipAccess = vip) }
        }
    }
    fun refreshLibrary(): Job {
        check()
        val expected = revision.get(); val cookie = credential(); val user = account?.userId.orEmpty()
        return library.start(scope, expected, revision::get) {
            withContext(Dispatchers.IO) { api.userPlaylists(cookie, user) }
        }
    }
    fun search(query: String) {
        searchQuery = query; searchJob?.cancel()
        searchJob = action {
            val expected = revision.get(); val cookie = credential(); val vip = account?.hasVipAccess == true
            loading = true
            try { val result = withContext(Dispatchers.IO) { api.search(query, cookie, vip) }; currentCoroutineContext().ensureActive(); check(expected); searchTracks = result }
            finally { loading = false }
        }
    }
    fun openPlaylist(value: NeteasePlaylist) {
        environment.host.contentUi?.let { shared ->
            shared.openCollection(value.toCollection(account?.userId)) { if (account != null) refreshLibrary() }
            return
        }
        selectedPlaylist = value; playlistPage = null; open("playlist"); loadPlaylist(false)
    }
    fun loadPlaylist(next: Boolean) = action {
        val playlist = selectedPlaylist ?: return@action
        val previous = if (next) playlistPage else null
        val expected = revision.get(); val cookie = credential(); val vip = account?.hasVipAccess == true
        loading = true
        try {
            val incoming = withContext(Dispatchers.IO) { api.playlistPage(playlist, cookie, vip, previous?.next) }
            check(expected); if (selectedPlaylist?.id != playlist.id) return@action
            playlistPage = mergePlaylistTrackPage(previous, incoming); selectedPlaylist = playlistPage!!.playlist
        } finally { loading = false }
    }
    fun play(tracks: List<NeteaseTrack>, index: Int = 0) { check(); environment.host.playDetailed(tracks.map { it.toDetails(account?.userId) }, index) }
    override fun close() { if (!closed) { closed = true; loginGeneration.incrementAndGet(); revision.incrementAndGet(); job.cancel(); registration.close() } }
}

internal fun NeteaseAccount.toSourceAccount() = SourceAccount(userId, nickname, avatarUrl,
    if (hasVipAccess) "VIP" else null, backgroundUrl, signature, follows.toLong(), followers.toLong())
