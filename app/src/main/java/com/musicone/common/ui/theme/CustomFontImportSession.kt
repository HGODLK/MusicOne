package com.musicone

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 从具体槽位触发导入时记住绑定目标，单字体导入成功后直接应用。 */
internal sealed interface CustomFontBindTarget {
    data object None : CustomFontBindTarget
    data class UiScript(val script: MusicOneScript) : CustomFontBindTarget
    data object UiAll : CustomFontBindTarget
    data class LyricScript(val script: MusicOneScript) : CustomFontBindTarget
    data object LyricAll : CustomFontBindTarget
    data object Clock : CustomFontBindTarget
}

internal data class CustomFontActiveTask(
    val label: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
) {
    val summary: String
        get() = if (downloadedBytes <= 0L) label
        else if (totalBytes > 0L) "$label（${formatFontFileSize(downloadedBytes)} / ${formatFontFileSize(totalBytes)}）"
        else "$label（${formatFontFileSize(downloadedBytes)}）"
}

internal data class CustomFontNotice(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

internal data class CustomFontGitHubForm(
    val target: CustomFontBindTarget,
    val url: String,
    val channel: CustomFontDownloadChannel,
    val ghProxyPrefix: String,
    val busy: Boolean = false,
    val error: String? = null,
)

internal data class CustomFontReleasePicker(
    val repoLabel: String,
    val assets: List<CustomFontRemoteAsset>,
    val channel: CustomFontDownloadChannel,
    val ghProxyPrefix: String,
    val target: CustomFontBindTarget,
)

internal data class CustomFontZipPicker(
    val zipFile: File,
    val archiveName: String,
    val entries: List<CustomFontArchiveEntry>,
    val target: CustomFontBindTarget,
)

/** 独立于二级页面生命周期的导入/下载会话，返回首页不静默断流，失败保留用户输入。 */
internal object CustomFontImportSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastGithubUrl = ""

    var activeTask by mutableStateOf<CustomFontActiveTask?>(null)
        private set
    var notice by mutableStateOf<CustomFontNotice?>(null)
        private set
    var githubForm by mutableStateOf<CustomFontGitHubForm?>(null)
        private set
    var releasePicker by mutableStateOf<CustomFontReleasePicker?>(null)
        private set
    var zipPicker by mutableStateOf<CustomFontZipPicker?>(null)
        private set
    var confirmDeleteRecords by mutableStateOf<List<CustomFontRecord>>(emptyList())
        private set
    var confirmReset by mutableStateOf(false)
        private set

    val hasOverlay: Boolean
        get() = githubForm != null || releasePicker != null || zipPicker != null || confirmDeleteRecords.isNotEmpty() || confirmReset

    val busy: Boolean
        get() = activeTask != null || githubForm?.busy == true

    fun clearNotice() { notice = null }

    fun postNotice(text: String, isError: Boolean = false) {
        notice = CustomFontNotice(text, isError)
    }

    fun openGitHubForm(target: CustomFontBindTarget = CustomFontBindTarget.None) {
        val cfg = CustomFontStore.config
        githubForm = CustomFontGitHubForm(
            target = target,
            url = lastGithubUrl,
            channel = cfg.downloadChannel,
            ghProxyPrefix = cfg.ghProxyPrefix,
        )
    }

    fun updateGitHubForm(transform: (CustomFontGitHubForm) -> CustomFontGitHubForm) {
        val current = githubForm ?: return
        val next = transform(current)
        lastGithubUrl = next.url
        githubForm = next
    }

    fun requestDelete(context: Context, record: CustomFontRecord) {
        requestDeleteBatch(context, listOf(record))
    }

    /** 单删未绑定字体直接移除；已绑定字体或批量多选删除均通过毛玻璃弹层二次确认。 */
    fun requestDeleteBatch(context: Context, records: List<CustomFontRecord>) {
        val currentIds = CustomFontStore.config.records.mapTo(HashSet()) { it.id }
        val valid = records.distinctBy { it.id }.filter { it.id in currentIds }
        if (valid.isEmpty()) return
        if (valid.size == 1 && CustomFontStore.config.boundLabels(valid[0].id).isEmpty()) {
            CustomFontStore.deleteFonts(context, listOf(valid[0].id))
            postNotice("已移除「${valid[0].displayName}」")
        } else {
            confirmDeleteRecords = valid
        }
    }

    fun confirmDeletePending(context: Context) {
        val targets = confirmDeleteRecords
        if (targets.isEmpty()) return
        confirmDeleteRecords = emptyList()
        val removed = CustomFontStore.deleteFonts(context, targets.map { it.id })
        if (removed == 1) {
            postNotice("已移除「${targets[0].displayName}」")
        } else if (removed > 1) {
            postNotice("已移除 $removed 个字体")
        }
    }

    fun openResetConfirm() { confirmReset = true }

    fun confirmResetAll(context: Context) {
        confirmReset = false
        CustomFontStore.resetAll(context)
        postNotice("已重置全部自定义字体")
    }

    fun dismissOverlay() {
        if (busy) return
        githubForm = null
        releasePicker = null
        zipPicker?.zipFile?.delete()
        zipPicker = null
        confirmDeleteRecords = emptyList()
        confirmReset = false
    }

    fun cancelActive() {
        job?.cancel()
        job = null
        activeTask = null
        githubForm = githubForm?.copy(busy = false)
        postNotice("已取消当前任务")
    }

    fun submitGitHubForm(context: Context) {
        val form = githubForm ?: return
        if (form.busy) return
        lastGithubUrl = form.url
        if (form.channel == CustomFontDownloadChannel.GHPROXY) {
            validateGhProxyPrefix(form.ghProxyPrefix)?.let { msg ->
                githubForm = form.copy(error = msg)
                return
            }
        }
        val normalizedPrefix = normalizeGhProxyPrefix(form.ghProxyPrefix)
        when (val parsed = parseCustomFontLink(form.url)) {
            is CustomFontLinkTarget.Invalid -> {
                githubForm = form.copy(error = parsed.reason)
            }
            is CustomFontLinkTarget.DirectFile -> {
                CustomFontStore.updateDownloadOptions(form.channel, normalizedPrefix)
                githubForm = null
                startDownload(context.applicationContext, parsed.originalUrl, parsed.suggestedName, form.channel, normalizedPrefix, form.target)
            }
            is CustomFontLinkTarget.GitHubRelease -> {
                CustomFontStore.updateDownloadOptions(form.channel, normalizedPrefix)
                githubForm = form.copy(busy = true, error = null)
                job?.cancel()
                job = scope.launch {
                    try {
                        val assets = fetchGitHubFontReleaseAssets(parsed)
                        githubForm = null
                        if (assets.size == 1) {
                            startDownload(context.applicationContext, assets[0].downloadUrl, assets[0].name,
                                form.channel, normalizedPrefix, form.target)
                        } else {
                            releasePicker = CustomFontReleasePicker("${parsed.owner}/${parsed.repo}", assets,
                                form.channel, normalizedPrefix, form.target)
                        }
                    } catch (_: CancellationException) {
                        githubForm = githubForm?.copy(busy = false)
                    } catch (error: Throwable) {
                        // 失败时留在弹层内保留已输入链接，方便直接改通道或粘贴直链重试。
                        githubForm = (githubForm ?: form).copy(busy = false, error = error.message ?: "获取 Release 失败")
                    }
                }
            }
        }
    }

    fun selectReleaseAsset(context: Context, asset: CustomFontRemoteAsset) {
        val picker = releasePicker ?: return
        releasePicker = null
        startDownload(context.applicationContext, asset.downloadUrl, asset.name, picker.channel, picker.ghProxyPrefix, picker.target)
    }

    fun confirmZipSelection(context: Context, selectedPaths: Set<String>) {
        val picker = zipPicker ?: return
        zipPicker = null
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            activeTask = CustomFontActiveTask("正在解压并校验字体…")
            try {
                val imported = extractAndImportFontZipEntries(app, picker.zipFile, selectedPaths)
                if (imported.size == 1) {
                    applyBinding(picker.target, imported[0].id)
                    postNotice("已导入「${imported[0].displayName}」")
                } else if (imported.isNotEmpty()) {
                    postNotice("已导入 ${imported.size} 个字体，可在上方槽位分配")
                } else {
                    postNotice("所选文件均未通过字体校验", isError = true)
                }
            } catch (_: CancellationException) {
                postNotice("已取消解压导入")
            } catch (error: Throwable) {
                postNotice(error.message ?: "解压导入失败", isError = true)
            } finally {
                picker.zipFile.delete()
                activeTask = null
            }
        }
    }

    fun importLocalUris(context: Context, uris: List<Uri>, target: CustomFontBindTarget) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            activeTask = CustomFontActiveTask("正在导入本地文件…")
            try {
                if (uris.size == 1) {
                    val (temp, name) = CustomFontStore.copyUriToTemp(app, uris[0])
                    handleTempFile(app, temp, name, target)
                } else {
                    var okCount = 0
                    var failCount = 0
                    var lastId: String? = null
                    for (uri in uris) {
                        val (temp, name) = CustomFontStore.copyUriToTemp(app, uri)
                        try {
                            if (isZipFileName(name) || isZipHeader(temp)) {
                                val entries = withContext(Dispatchers.IO) { inspectFontZip(temp) }
                                if (entries.isEmpty()) failCount++ else {
                                    val recs = extractAndImportFontZipEntries(app, temp, entries.map { it.entryPath }.toSet())
                                    okCount += recs.size
                                    recs.lastOrNull()?.let { lastId = it.id }
                                }
                            } else {
                                val rec = CustomFontStore.importValidatedFile(app, temp, name)
                                okCount++
                                lastId = rec.id
                            }
                        } catch (_: Throwable) {
                            failCount++
                        } finally {
                            temp.delete()
                        }
                    }
                    if (okCount == 1 && lastId != null) applyBinding(target, lastId!!)
                    val text = if (failCount > 0) "已导入 $okCount 个字体，跳过 $failCount 个无效文件" else "已导入 $okCount 个字体"
                    postNotice(text, isError = okCount == 0)
                }
            } catch (_: CancellationException) {
                postNotice("已取消导入")
            } catch (error: Throwable) {
                postNotice(error.message ?: "本地导入失败", isError = true)
            } finally {
                activeTask = null
            }
        }
    }

    private fun startDownload(
        appContext: Context,
        originalUrl: String,
        suggestedName: String,
        channel: CustomFontDownloadChannel,
        ghProxyPrefix: String,
        target: CustomFontBindTarget,
    ) {
        val finalUrl = resolveCustomFontDownloadUrl(originalUrl, channel, ghProxyPrefix)
        job?.cancel()
        job = scope.launch {
            activeTask = CustomFontActiveTask("正在下载 $suggestedName")
            try {
                val (temp, resolvedName) = downloadCustomFontToTemp(appContext, finalUrl) { done, total ->
                    activeTask = CustomFontActiveTask("正在下载 $suggestedName", done, total)
                }
                activeTask = CustomFontActiveTask("正在校验字体…")
                handleTempFile(appContext, temp, resolvedName.ifBlank { suggestedName }, target)
            } catch (_: CancellationException) {
                postNotice("已取消下载")
            } catch (error: Throwable) {
                postNotice(error.message ?: "下载失败，可点 GitHub 导入切换通道重试", isError = true)
            } finally {
                activeTask = null
            }
        }
    }

    private suspend fun handleTempFile(appContext: Context, tempFile: File, fileName: String, target: CustomFontBindTarget) {
        if (isZipFileName(fileName) || isZipHeader(tempFile)) {
            val entries = withContext(Dispatchers.IO) { inspectFontZip(tempFile) }
            if (entries.isEmpty()) {
                tempFile.delete()
                postNotice("压缩包内未找到有效的 .ttf / .otf / .ttc 字体", isError = true)
                return
            }
            if (entries.size == 1) {
                try {
                    val imported = extractAndImportFontZipEntries(appContext, tempFile, setOf(entries[0].entryPath))
                    imported.firstOrNull()?.let { rec ->
                        applyBinding(target, rec.id)
                        postNotice("已导入「${rec.displayName}」")
                    }
                } finally {
                    tempFile.delete()
                }
            } else {
                zipPicker?.zipFile?.delete()
                zipPicker = CustomFontZipPicker(tempFile, fileName, entries, target)
            }
        } else {
            try {
                val record = CustomFontStore.importValidatedFile(appContext, tempFile, fileName)
                applyBinding(target, record.id)
                postNotice("已导入「${record.displayName}」")
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun applyBinding(target: CustomFontBindTarget, fontId: String) {
        when (target) {
            CustomFontBindTarget.None -> Unit
            is CustomFontBindTarget.UiScript -> CustomFontStore.setUiFont(target.script, fontId)
            CustomFontBindTarget.UiAll -> CustomFontStore.applyUiFontToAll(fontId)
            is CustomFontBindTarget.LyricScript -> CustomFontStore.setLyricSlot(target.script, CustomLyricSlot.Font(fontId))
            CustomFontBindTarget.LyricAll -> CustomFontStore.applyLyricSlotToAll(CustomLyricSlot.Font(fontId))
            CustomFontBindTarget.Clock -> CustomFontStore.setClockFont(CustomClockFontBinding.Font(fontId))
        }
    }
}
