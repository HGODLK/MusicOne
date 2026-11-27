package com.musicone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicone.source.sdk.SourceKind
import com.musicone.source.sdk.SourceSessionStatus
import com.musicone.sourceintegration.SourceApplicationHost
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 登录协议和续期全归音源包管，这里只监听公开的账号状态和主音源选择。 */
internal class PlatformSettingsViewModel @JvmOverloads constructor(application: Application, private val fixedSource: MusicSource? = null) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val sources = SourcePackageController.get(application)
    private val host = SourceApplicationHost.get(application)
    private val mutable = MutableStateFlow(PlatformSettingsUiState(selectedSource = preferences.selectedSource()))
    val state = mutable.asStateFlow()
    private var refresh: Job? = null
    private val refreshSchedule = SourceAccountRefreshSchedule()
    private val pendingLogin = PendingSourceLogin()
    private var observedAccount: Any? = null
    private val homeSessionRevision = HomeSessionRevision()
    init {
        readState()
        viewModelScope.launch { host.accountRevision.collect { readState() } }
        viewModelScope.launch { host.destination.collect { if (it == null) settlePendingLogin() } }
        viewModelScope.launch { sources.installed.collect { readState() } }
        viewModelScope.launch { sources.startup.collect {
            readState()
            if (!it.initializing) refreshSessionIfDue()
        } }
    }
    private fun readState() {
        if (sources.startup.value.initializing) {
            val selected = fixedSource ?: preferences.selectedSource()
            mutable.update { it.copy(selectedSource = selected, loginSource = selected,
                sessionStatus = if (selected == MusicSource.LOCAL) SessionStatus.CONNECTED else SessionStatus.CHECKING) }
            return
        }
        val saved = preferences.read()
        val source = fixedSource ?: saved.selectedSource
        val session = saved.session(source)
        val account = session.account
        val installed = source == MusicSource.LOCAL || sources.running(source.id) != null
        val homeRevision = homeSessionRevision.update(session, installed)
        observedAccount = accountFingerprint(source)
        val settingsSourceIds = MAIN_MUSIC_SOURCES.filter { it.supportsSourceSettings }.mapTo(mutableSetOf()) { it.id }
        mutable.update { it.copy(selectedSource = source, sourceSelected = saved.sourceSelected,
            settingsSourceIds = settingsSourceIds, loginSource = source, account = account.takeIf { installed },
            sessionStatus = if (installed && (!source.requiresAccount || account != null)) SessionStatus.CONNECTED else SessionStatus.SIGNED_OUT,
            sessionRevision = it.sessionRevision + 1, homeSessionRevision = homeRevision, working = false) }
    }
    fun selectSource(source: MusicSource) {
        if (source != MusicSource.LOCAL && sources.running(source.id)?.entry?.descriptor?.kind != SourceKind.MAIN) return
        refresh?.cancel()
        preferences.saveSource(source)
        readState()
    }
    /** 需要登录的音源等用户真正登完再切为主音源，中途取消登录就保持原来的选择。 */
    fun selectAfterLogin(source: MusicSource) {
        if (sources.running(source.id)?.entry?.authentication == null) return
        pendingLogin.begin(source)
        startLogin(source)
    }
    fun startLogin(source: MusicSource = state.value.selectedSource) {
        if (sources.running(source.id)?.entry?.authentication == null) return
        host.openDestination("source-authentication", mapOf("sourceId" to source.id))
    }
    /** 音源设置复用登录页同一套外部页面层，不切换当前主音源。 */
    fun openSourceSettings(source: MusicSource) {
        if (!source.supportsSourceSettings) return
        host.openDestination("source-settings", mapOf("sourceId" to source.id))
    }
    // 有些音源不会主动回调登录成功，统一在关闭登录页时查一下当前账号。
    private fun settlePendingLogin() {
        pendingLogin.settle { preferences.readSession(it).account != null }?.let(::selectSource)
    }
    private fun accountFingerprint(source: MusicSource): Any? =
        sources.running(source.id)?.entry?.authentication?.currentAccount()?.let { Triple(it.status, it.account?.id, it.revision) }
    // 只有公开的账号或登录状态真的变了才刷新界面，同账号静默续期不重刷。
    private fun syncAccountState() {
        if (!sources.startup.value.initializing && accountFingerprint(state.value.selectedSource) != observedAccount) readState()
    }
    fun logout() {
        val id = state.value.selectedSource.id
        val auth = sources.running(id)?.entry?.authentication ?: return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { auth.logout() }; host.accountRevision.value++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "退出登录失败") } }
        }
    }
    fun refreshSessionIfDue() {
        syncAccountState()
        val id = state.value.selectedSource.id
        if (refresh?.isActive == true) return
        val auth = sources.running(id)?.entry?.authentication ?: return
        val account = auth.currentAccount()
        if (account.account == null) return
        if (!refreshSchedule.due(id, System.currentTimeMillis(), account.status == SourceSessionStatus.EXPIRED)) return
        refreshSchedule.attempted(id, System.currentTimeMillis())
        refresh = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { auth.refreshAccount() }
                refreshSchedule.succeeded(id, System.currentTimeMillis())
                readState()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(message = "暂时无法验证登录状态") }
                syncAccountState()
            }
        }
    }
}
