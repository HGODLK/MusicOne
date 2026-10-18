package com.musicone.demo

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
internal fun MusicLibraryHost(playback: MusicPlaybackRuntime, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ui = remember { MusicLibraryUiController(context, scope) }
    val snapshot by ui.store.state.collectAsStateWithLifecycle()
    var lyricTarget by remember { mutableStateOf<MusicTrack?>(null) }
    var coverTarget by remember { mutableStateOf<MusicTrack?>(null) }
    var relinkTarget by remember { mutableStateOf<MusicTrack?>(null) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var relink by remember { mutableStateOf<LibraryFolder?>(null) }
    var exportAudio by remember { mutableStateOf(false) }
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(ui) { ui.store.refresh() }
    SideEffect { ui.snapshot = snapshot }
    PrepareLibraryArtwork(snapshot)
    ObserveLibraryFileAvailability(snapshot, ui.store)
    LaunchedEffect(snapshot.tracks) { playback.refreshLibraryMetadata(snapshot.tracks) }
    LaunchedEffect(snapshot.lyricRevision) { playback.refreshManualLyrics() }
    LaunchedEffect(ui.message) { ui.message?.takeIf(String::isNotBlank)?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ui.work("正在导入文件…") { ui.imported(ui.importer.files(uris, ui.copyFiles) { ui.progress = it }) }
    }
    val relinkFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = relinkTarget; relinkTarget = null
        if (uri != null && target != null) ui.work("正在重新关联文件…") { ui.importer.relink(target, uri); ui.message = "文件已重新关联" }
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val folder = relink; relink = null
        if (uri != null) ui.work("正在导入目录…") { ui.imported(ui.importer.directory(uri, folder?.copyFiles ?: ui.copyFiles, { ui.progress = it }, folder)) }
    }
    val lyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = lyricTarget; lyricTarget = null
        if (uri != null && target != null) ui.importLyrics(target, uri)
    }
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) coverTarget = null else cropUri = uri
    }
    val archive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ui.work("正在读取归档…") { ui.preview = ui.archive.preview(uri) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) ui.work("正在导出归档…") { ui.archive.export(uri, exportAudio); ui.message = "归档已导出" }
    }
    val archiveDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.onSuccess { ui.mergeArchive(uri) }.onFailure { ui.message = "无法取得目录授权，请重新选择" }
        }
    }
    SideEffect {
        ui.chooseFiles = { files.launch(arrayOf("audio/*", "application/octet-stream")) }
        ui.chooseDirectory = { relink = it; directory.launch(null) }
        // 不同文件管理器给 .lrc 返回的 MIME 类型不一致，先放宽选择范围，再由歌词时间轴校验内容。
        ui.chooseLyrics = { lyricTarget = it; lyrics.launch(arrayOf("*/*")) }
        ui.chooseCover = { coverTarget = it; cover.launch(arrayOf("image/*")) }
        ui.chooseRelinkFile = { relinkTarget = it; relinkFile.launch(arrayOf("audio/*", "application/octet-stream")) }
        ui.chooseArchive = { archive.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ui.exportArchive = { exportAudio = it; export.launch("MusicOne-${System.currentTimeMillis()}.msco") }
        ui.chooseArchiveDirectory = { archiveDirectory.launch(null) }
    }
    val playbackActions = remember(playback) { LibraryPlaybackActions(playback::playTrack, { playback.playPlaylist(it) }) }
    CompositionLocalProvider(LocalMusicLibraryUi provides ui, LocalLibraryPlaybackActions provides playbackActions) {
        PlaybackSettingsMenuHost {
            Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
                Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, ui.form != null || cropUri != null || ui.preview != null)) { content() }
                LibraryFormsOverlay(ui, backdrop, bounds)
                cropUri?.let { uri ->
                    LibraryArtworkCrop(uri, backdrop, bounds, onDismiss = { cropUri = null; coverTarget = null }) { bitmap, selection ->
                        val target = coverTarget
                        cropUri = null; coverTarget = null
                        if (target != null) ui.work("正在保存封面…") {
                            val file = File(ui.store.assets, "artwork/${target.id}-${UUID.randomUUID()}.jpg")
                            var committed = false
                            try { withContext(Dispatchers.IO) {
                                val rect = neteaseCropRect(bitmap.width, bitmap.height, selection)
                                val cropped = Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
                                val scaled = Bitmap.createScaledBitmap(cropped, 1024, 1024, true)
                                try {
                                    file.parentFile?.mkdirs(); file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                                } finally { if (scaled !== cropped) scaled.recycle(); if (cropped !== bitmap) cropped.recycle() }
                                val old = ui.store.overrides(target.id)
                                ui.store.updateOverrides(target.id, old.first, old.second, Uri.fromFile(file).toString(), onCommitted = { committed = true })
                            } } finally { if (!committed) file.delete() }
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(ui) { onDispose { ui.preview?.folder?.deleteRecursively() } }
}
