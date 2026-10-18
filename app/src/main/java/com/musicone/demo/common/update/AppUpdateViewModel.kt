package com.musicone.demo

import android.app.Application
import android.os.SystemClock
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

internal data class AppUpdateUiState(
    val installedVersion: String,
    val latest: MusicOneRelease? = null,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val error: String? = null,
    val prompt: MusicOneRelease? = null,
) {
    val available: MusicOneRelease? get() {
        val current = MusicOneVersion.installed(installedVersion) ?: return null
        return latest?.takeIf { it.version > current }
    }
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppUpdatePreferences(application)
    private val repository = MusicOneReleaseRepository()
    private val gate = UpdatePromptGate()
    private val mutableState = MutableStateFlow(AppUpdateUiState(
        installedVersion = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty(),
        latest = preferences.latest(),
    ))
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var openingRevision = 0L
    private var promptRecorded = false

    fun openApplication() {
        openingRevision++
        promptRecorded = false
        mutableState.update { it.copy(prompt = null) }
        gate.open(SystemClock.elapsedRealtime())
        checkForUpdates(automatic = true)
    }

    fun setForeground(value: Boolean) {
        gate.setForeground(value)
        if (value) offerPrompt() else {
            mutableState.update { it.copy(prompt = null) }
            promptRecorded = false
        }
    }

    fun finishOpening() {
        gate.close()
        if (!promptRecorded) mutableState.update { it.copy(prompt = null) }
    }

    fun checkManually() {
        finishOpening()
        checkForUpdates(automatic = false)
    }

    fun showPromptManually(release: MusicOneRelease) {
        promptRecorded = true
        mutableState.update { it.copy(prompt = release) }
    }

    private fun checkForUpdates(automatic: Boolean) {
        if (checkJob?.isActive == true) return
        val revision = openingRevision
        checkJob = viewModelScope.launch {
            mutableState.update { it.copy(checking = true, error = null) }
            try {
                val latest = withContext(Dispatchers.IO) { repository.latest() }
                preferences.saveLatest(latest)
                mutableState.update { it.copy(latest = latest, checked = true) }
                // 迟到结果只更新下载入口，不能在用户已经操作后重新打开提醒窗口。
                if (automatic && revision == openingRevision) offerPrompt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "暂时无法检查更新，点击重试") }
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    private fun offerPrompt() {
        val release = mutableState.value.available ?: return
        if (mutableState.value.prompt == null && canRenderPrompt()) {
            mutableState.update { it.copy(prompt = release) }
        }
    }

    fun canRenderPrompt(): Boolean = promptRecorded || gate.allows(
        SystemClock.elapsedRealtime(), System.currentTimeMillis(), preferences.lastShownAt(),
    )

    fun recordPromptShown() {
        if (promptRecorded || mutableState.value.prompt == null) return
        preferences.markShown(System.currentTimeMillis())
        promptRecorded = true
        gate.close()
    }

    fun dismissPrompt() {
        gate.close()
        mutableState.update { it.copy(prompt = null) }
        promptRecorded = false
    }
}
