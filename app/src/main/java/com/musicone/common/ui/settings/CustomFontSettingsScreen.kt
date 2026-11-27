package com.musicone

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 自定义字体二级设置页：复用 MusicLibrarySettingsScreen 同款手机单栏 / 平板双栏骨架、
 * LibraryImportTile / LibrarySectionTabs / SettingsGroup / MusicOneBackdropGlass，不自造新控件。
 */
@Composable
internal fun CustomFontSettingsScreen(onBack: () -> Unit, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    remember(context) { CustomFontStore.initialize(context); true }
    val config = CustomFontStore.config
    val revision = CustomFontStore.revision
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingLocalTarget by remember { mutableStateOf<CustomFontBindTarget>(CustomFontBindTarget.None) }
    val backdrop = rememberGraphicsLayer()
    var hostBounds by remember { mutableStateOf(Rect.Zero) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val target = pendingLocalTarget
        pendingLocalTarget = CustomFontBindTarget.None
        CustomFontImportSession.importLocalUris(context, uris, target)
    }

    fun launchLocalImport(target: CustomFontBindTarget) {
        pendingLocalTarget = target
        localPicker.launch(arrayOf("*/*"))
    }

    PlaybackSettingsMenuHost(bottomInset = bottomInset) {
        val needsBackdrop = CustomFontImportSession.hasOverlay ||
            CustomFontImportSession.activeTask != null ||
            CustomFontImportSession.notice != null
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostBounds = it.boundsInRoot() }) {
            Surface(
                Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, needsBackdrop),
                color = MaterialTheme.colorScheme.background,
            ) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val wide = maxWidth >= 840.dp
                    val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
                    val extraBottom = if (CustomFontImportSession.activeTask != null || CustomFontImportSession.notice != null) 96.dp else 0.dp
                    LazyColumn(
                        Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = 20.dp, bottom = bottomInset + 24.dp + extraBottom),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item { SettingsHeader("自定义字体", onBack) }
                        if (wide) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(.95f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                        CustomFontPreviewGroup(selectedTab, config, revision)
                                        CustomFontImportGroup(
                                            config = config,
                                            busy = CustomFontImportSession.busy,
                                            onLocal = { launchLocalImport(CustomFontBindTarget.None) },
                                            onGitHub = { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.None) },
                                        )
                                        CustomFontLibraryGroup(config, CustomFontImportSession.busy)
                                    }
                                    Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                        CustomFontFallbackGroup(config)
                                        LibrarySectionTabs(selectedTab, listOf("界面", "歌词", "横屏时钟")) { selectedTab = it }
                                        CustomFontSlotSection(selectedTab, config, ::launchLocalImport)
                                    }
                                }
                            }
                        } else {
                            item { CustomFontPreviewGroup(selectedTab, config, revision) }
                            item {
                                CustomFontImportGroup(
                                    config = config,
                                    busy = CustomFontImportSession.busy,
                                    onLocal = { launchLocalImport(CustomFontBindTarget.None) },
                                    onGitHub = { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.None) },
                                )
                            }
                            item { CustomFontFallbackGroup(config) }
                            item { LibrarySectionTabs(selectedTab, listOf("界面", "歌词", "横屏时钟")) { selectedTab = it } }
                            item { CustomFontSlotSection(selectedTab, config, ::launchLocalImport) }
                            item { CustomFontLibraryGroup(config, CustomFontImportSession.busy) }
                        }
                    }
                }
            }

            // 固定在底部安全区上方的任务进度与瞬态提示，复用同款实时毛玻璃避免透明透底。
            CustomFontFloatingStatus(
                backdrop = backdrop,
                hostBounds = hostBounds,
                bottomInset = bottomInset,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            CustomFontOverlays(backdrop, hostBounds)
        }
    }
}

/**
 * 按语系逐段生成预览文本，避免整条多语种样张被单一 CJK 语系判定吞并，
 * 确保切换简／繁／日／韩／西文任意槽位时预览都能独立实时生效。
 */
private fun previewSegmentedLine(
    vararg segments: Pair<String, MusicOneScript>,
    render: (String, MusicOneScript) -> AnnotatedString,
): AnnotatedString = buildAnnotatedString {
    segments.forEachIndexed { index, (text, script) ->
        if (index > 0) append(" · ")
        append(render(text, script))
    }
}

@Composable
private fun CustomFontPreviewGroup(selectedTab: Int, config: CustomFontConfig, revision: Long) {
    val defaultClockFamily = remember { FontFamily(Font(R.font.fredoka_medium, FontWeight.Medium)) }
    val clockFamily = remember(config, revision, defaultClockFamily) {
        resolvePhoneLandscapeClockFontFamily(AppFontMode.CUSTOM, config, defaultClockFamily, CustomFontStore::fontFamily)
    }
    val uiLtr = remember(config, revision) {
        previewSegmentedLine(
            "简体中文" to MusicOneScript.SC,
            "繁體" to MusicOneScript.TC,
            "日本語" to MusicOneScript.JA,
            "한국어" to MusicOneScript.KO,
            "Music 123" to MusicOneScript.LATIN,
        ) { text, script -> musicOneUiAnnotatedString(text, hint = script, mode = AppFontMode.CUSTOM) }
    }
    val uiRtl = remember(config, revision) {
        musicOneUiAnnotatedString("در دل شب · أهلاً بك", hint = MusicOneScript.ARABIC, mode = AppFontMode.CUSTOM)
    }
    val lyricLtr = remember(config, revision) {
        previewSegmentedLine(
            "你好" to MusicOneScript.SC,
            "你好嗎" to MusicOneScript.TC,
            "こんにちは" to MusicOneScript.JA,
            "안녕하세요" to MusicOneScript.KO,
            "Hello" to MusicOneScript.LATIN,
        ) { text, script -> lyricOriginalAnnotatedString(text, hint = script, mode = AppFontMode.CUSTOM) }
    }
    val lyricRtl = remember(config, revision) {
        lyricOriginalAnnotatedString("سلام · مرحباً", hint = MusicOneScript.ARABIC, mode = AppFontMode.CUSTOM)
    }
    val lyricTranslation = remember(config, revision) {
        musicOneUiAnnotatedString("这是一句正经的测试歌词", hint = MusicOneScript.SC, mode = AppFontMode.CUSTOM)
    }

    LibrarySettingsGroup("实时预览") {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(musicMotion(200)) togetherWith fadeOut(musicMotion(160)) },
            label = "字体实时预览切换",
        ) { tab ->
            when (tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiLtr, style = MaterialTheme.typography.titleMedium)
                    Text(
                        uiRtl,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Right,
                            textDirection = TextDirection.Rtl,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lyricLtr, style = MusicOneTextStyles.lyricActive.copy(fontSize = 24.sp, lineHeight = 32.sp))
                    Text(
                        lyricRtl,
                        style = MusicOneTextStyles.lyricActive.copy(
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            textAlign = TextAlign.Right,
                            textDirection = TextDirection.Rtl,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        lyricTranslation,
                        style = MusicOneTextStyles.lyricTranslation,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        "12:34",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            fontFamily = clockFamily,
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            letterSpacing = (-.025f).em,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ":56",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 19.sp,
                            lineHeight = 24.sp,
                            fontFamily = clockFamily,
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            letterSpacing = (-.025f).em,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomFontImportGroup(
    config: CustomFontConfig,
    busy: Boolean,
    onLocal: () -> Unit,
    onGitHub: () -> Unit,
) {
    LibrarySettingsGroup("导入字体") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryImportTile("从本地导入", Icons.Default.FolderOpen, !busy, onLocal, Modifier.weight(1f))
            LibraryImportTile("从 GitHub 导入", Icons.Default.CloudDownload, !busy, onGitHub, Modifier.weight(1f))
        }
        Text(
            "支持 .ttf / .otf / .ttc 及 .zip 字体包 · GitHub 当前线路：${config.downloadChannel.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomFontFallbackGroup(config: CustomFontConfig) {
    SettingsGroup("默认回退") {
        SettingsMenuRow(
            title = "未指定槽位回退",
            value = config.fallback.label,
            subtitle = "未绑定自定义字体的语系沿用此风格；未修改时与默认模式一致",
            options = CustomFontFallback.entries.map { fb ->
                PlaybackSettingsMenuOption(fb.label, fb == config.fallback) {
                    CustomFontStore.updateFallback(fb)
                }
            },
        )
    }
}

@Composable
private fun CustomFontSlotSection(
    selectedTab: Int,
    config: CustomFontConfig,
    onLocalImport: (CustomFontBindTarget) -> Unit,
) {
    when (selectedTab) {
        0 -> SettingsGroup("界面字体") {
            MusicOneScript.entries.forEach { script ->
                val selectedId = config.resolveUiFontId(script)
                val label = config.recordById(selectedId)?.displayName ?: "跟随回退（${config.fallback.label}）"
                SettingsMenuRow(
                    title = script.displayLabel,
                    value = label,
                    menuTitle = "界面 · ${script.displayLabel}",
                    options = buildList {
                        add(PlaybackSettingsMenuOption("跟随回退（${config.fallback.label}）", selectedId == null) {
                            CustomFontStore.setUiFont(script, null)
                        })
                        config.records.forEach { rec ->
                            add(PlaybackSettingsMenuOption(rec.displayName, selectedId == rec.id, description = formatFontFileSize(rec.sizeBytes)) {
                                CustomFontStore.setUiFont(script, rec.id)
                            })
                        }
                        add(PlaybackSettingsMenuOption("从本地导入并应用…", false) { onLocalImport(CustomFontBindTarget.UiScript(script)) })
                        add(PlaybackSettingsMenuOption("从 GitHub 导入并应用…", false) { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.UiScript(script)) })
                    },
                )
            }
            SettingsMenuRow(
                title = "全部界面语系",
                value = "批量设置",
                subtitle = "一键应用同一字体或统一恢复回退",
                options = buildList {
                    add(PlaybackSettingsMenuOption("全部恢复回退（${config.fallback.label}）", config.uiFonts.isEmpty()) {
                        CustomFontStore.applyUiFontToAll(null)
                    })
                    config.records.forEach { rec ->
                        val allMatch = config.uiFonts.size == MusicOneScript.entries.size && config.uiFonts.values.all { it == rec.id }
                        add(PlaybackSettingsMenuOption(rec.displayName, allMatch) { CustomFontStore.applyUiFontToAll(rec.id) })
                    }
                    add(PlaybackSettingsMenuOption("从本地导入并应用到全部…", false) { onLocalImport(CustomFontBindTarget.UiAll) })
                    add(PlaybackSettingsMenuOption("从 GitHub 导入并应用到全部…", false) { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.UiAll) })
                },
            )
        }
        1 -> SettingsGroup("歌词字体") {
            MusicOneScript.entries.forEach { script ->
                val slot = config.lyricFonts[script]
                val label = when (slot) {
                    null -> "跟随回退（${config.fallback.label}）"
                    CustomLyricSlot.FollowUi -> "跟随界面"
                    is CustomLyricSlot.Font -> config.recordById(slot.fontId)?.displayName ?: "跟随回退（${config.fallback.label}）"
                }
                SettingsMenuRow(
                    title = script.displayLabel,
                    value = label,
                    menuTitle = "歌词 · ${script.displayLabel}",
                    options = buildList {
                        add(PlaybackSettingsMenuOption("跟随回退（${config.fallback.label}）", slot == null) {
                            CustomFontStore.setLyricSlot(script, null)
                        })
                        add(PlaybackSettingsMenuOption("跟随对应界面语系", slot == CustomLyricSlot.FollowUi) {
                            CustomFontStore.setLyricSlot(script, CustomLyricSlot.FollowUi)
                        })
                        config.records.forEach { rec ->
                            val isCurrent = slot is CustomLyricSlot.Font && slot.fontId == rec.id
                            add(PlaybackSettingsMenuOption(rec.displayName, isCurrent, description = formatFontFileSize(rec.sizeBytes)) {
                                CustomFontStore.setLyricSlot(script, CustomLyricSlot.Font(rec.id))
                            })
                        }
                        add(PlaybackSettingsMenuOption("从本地导入并应用…", false) { onLocalImport(CustomFontBindTarget.LyricScript(script)) })
                        add(PlaybackSettingsMenuOption("从 GitHub 导入并应用…", false) { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.LyricScript(script)) })
                    },
                )
            }
            SettingsMenuRow(
                title = "全部歌词语系",
                value = "批量设置",
                subtitle = "同步作用于播放页歌词原文与桌面歌词（跟随应用时）",
                options = buildList {
                    add(PlaybackSettingsMenuOption("全部恢复回退（${config.fallback.label}）", config.lyricFonts.isEmpty()) {
                        CustomFontStore.applyLyricSlotToAll(null)
                    })
                    val allFollowUi = config.lyricFonts.size == MusicOneScript.entries.size &&
                        config.lyricFonts.values.all { it == CustomLyricSlot.FollowUi }
                    add(PlaybackSettingsMenuOption("全部跟随界面字体", allFollowUi) {
                        CustomFontStore.applyLyricSlotToAll(CustomLyricSlot.FollowUi)
                    })
                    config.records.forEach { rec ->
                        val allMatch = config.lyricFonts.size == MusicOneScript.entries.size &&
                            config.lyricFonts.values.all { it is CustomLyricSlot.Font && it.fontId == rec.id }
                        add(PlaybackSettingsMenuOption(rec.displayName, allMatch) {
                            CustomFontStore.applyLyricSlotToAll(CustomLyricSlot.Font(rec.id))
                        })
                    }
                    add(PlaybackSettingsMenuOption("从本地导入并应用到全部…", false) { onLocalImport(CustomFontBindTarget.LyricAll) })
                    add(PlaybackSettingsMenuOption("从 GitHub 导入并应用到全部…", false) { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.LyricAll) })
                },
            )
        }
        else -> SettingsGroup("横屏时钟") {
            val clock = config.clockFont
            val clockLabel = when (clock) {
                CustomClockFontBinding.Default -> "应用默认（Fredoka）"
                CustomClockFontBinding.FollowUiLatin -> "跟随界面西文"
                CustomClockFontBinding.FollowLyricLatin -> "跟随歌词西文"
                CustomClockFontBinding.System -> "跟随系统"
                is CustomClockFontBinding.Font -> config.recordById(clock.fontId)?.displayName ?: "应用默认（Fredoka）"
            }
            SettingsMenuRow(
                title = "横屏时钟字体",
                value = clockLabel,
                subtitle = "仅在「字体」设为自定义时生效",
                options = buildList {
                    add(PlaybackSettingsMenuOption("应用默认（Fredoka）", clock == CustomClockFontBinding.Default) {
                        CustomFontStore.setClockFont(CustomClockFontBinding.Default)
                    })
                    add(PlaybackSettingsMenuOption("跟随界面西文", clock == CustomClockFontBinding.FollowUiLatin) {
                        CustomFontStore.setClockFont(CustomClockFontBinding.FollowUiLatin)
                    })
                    add(PlaybackSettingsMenuOption("跟随歌词西文", clock == CustomClockFontBinding.FollowLyricLatin) {
                        CustomFontStore.setClockFont(CustomClockFontBinding.FollowLyricLatin)
                    })
                    add(PlaybackSettingsMenuOption("跟随系统", clock == CustomClockFontBinding.System) {
                        CustomFontStore.setClockFont(CustomClockFontBinding.System)
                    })
                    config.records.forEach { rec ->
                        val isCurrent = clock is CustomClockFontBinding.Font && clock.fontId == rec.id
                        add(PlaybackSettingsMenuOption(rec.displayName, isCurrent, description = formatFontFileSize(rec.sizeBytes)) {
                            CustomFontStore.setClockFont(CustomClockFontBinding.Font(rec.id))
                        })
                    }
                    add(PlaybackSettingsMenuOption("从本地导入并应用…", false) { onLocalImport(CustomFontBindTarget.Clock) })
                    add(PlaybackSettingsMenuOption("从 GitHub 导入并应用…", false) { CustomFontImportSession.openGitHubForm(CustomFontBindTarget.Clock) })
                },
            )
        }
    }
}

@Composable
private fun CustomFontLibraryGroup(config: CustomFontConfig, busy: Boolean) {
    val context = LocalContext.current
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var lastCount by remember { mutableIntStateOf(config.records.size) }

    LaunchedEffect(config.records) {
        val validIds = config.records.mapTo(HashSet()) { it.id }
        selectedIds.removeAll { it !in validIds }
        if (config.records.isEmpty() || (selectionMode && config.records.size < lastCount)) {
            selectionMode = false
            selectedIds.clear()
        }
        lastCount = config.records.size
    }

    if (selectionMode && !CustomFontImportSession.hasOverlay) {
        BackHandler {
            selectionMode = false
            selectedIds.clear()
        }
    }

    SettingsGroup(if (selectionMode) "已导入字体库（已选 ${selectedIds.size}）" else "已导入字体库") {
        if (config.records.isEmpty()) {
            Text(
                "尚未导入字体，可使用上方本地导入或 GitHub 链接导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsRowInset, vertical = 14.dp),
            )
        } else {
            if (selectionMode) {
                val allSelected = selectedIds.size == config.records.size && config.records.isNotEmpty()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowInset, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LibraryActionButton(
                            title = if (allSelected) "取消全选" else "全选",
                            onClick = {
                                if (allSelected) selectedIds.clear() else {
                                    selectedIds.clear()
                                    config.records.forEach { selectedIds.add(it.id) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            primary = false,
                        )
                        LibraryActionButton(
                            title = "完成",
                            onClick = {
                                selectionMode = false
                                selectedIds.clear()
                            },
                            modifier = Modifier.weight(1f),
                            primary = false,
                        )
                    }
                    LibraryActionButton(
                        title = if (selectedIds.isEmpty()) "请选择要移除的字体" else "删除已选（${selectedIds.size}）",
                        onClick = {
                            val targets = config.records.filter { it.id in selectedIds }
                            CustomFontImportSession.requestDeleteBatch(context, targets)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedIds.isNotEmpty() && !busy,
                        primary = true,
                    )
                }
            }
            config.records.forEach { rec ->
                val bound = config.boundLabels(rec.id)
                val family = remember(rec.id, CustomFontStore.revision) { CustomFontStore.fontFamily(rec.id) }
                val sub = buildString {
                    append(formatFontFileSize(rec.sizeBytes))
                    if (bound.isNotEmpty()) append(" · ${bound.joinToString("、")}") else append(" · 未绑定")
                }
                val checked = rec.id in selectedIds
                val rowModifier = if (selectionMode) {
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .toggleable(checked, enabled = !busy, role = Role.Checkbox) { next ->
                            if (next) { if (rec.id !in selectedIds) selectedIds.add(rec.id) }
                            else selectedIds.remove(rec.id)
                        }
                        .padding(horizontal = SettingsRowInset, vertical = 10.dp)
                } else {
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .pointerInput(rec.id, busy) {
                            detectTapGestures(onLongPress = {
                                if (!busy) {
                                    selectionMode = true
                                    selectedIds.clear()
                                    selectedIds.add(rec.id)
                                }
                            })
                        }
                        .padding(start = SettingsRowInset, end = 4.dp, top = 10.dp, bottom = 10.dp)
                }
                Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) {
                        Icon(
                            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            rec.displayName,
                            style = MaterialTheme.typography.titleMedium.let { if (family != null) it.copy(fontFamily = family) else it },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!selectionMode) {
                        IconButton(enabled = !busy, onClick = { CustomFontImportSession.requestDelete(context, rec) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "移除 ${rec.displayName}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (!selectionMode && config.records.size >= 2) {
            LibraryActionRow("多选删除", "批量选择或长按字体项进行移除", enabled = !busy) {
                selectionMode = true
                selectedIds.clear()
            }
        }
        if (!selectionMode && (config.records.isNotEmpty() || config.hasActiveOverrides())) {
            LibraryActionRow("重置自定义字体", "删除全部已导入字体并恢复默认", enabled = !busy) {
                CustomFontImportSession.openResetConfirm()
            }
        }
    }
}

@Composable
private fun CustomFontFloatingStatus(
    backdrop: GraphicsLayer,
    hostBounds: Rect,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val task = CustomFontImportSession.activeTask
    val notice = CustomFontImportSession.notice
    if (task == null && notice == null) return
    Box(
        modifier = modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (task != null) {
            MusicOneBackdropGlass(
                backdropLayer = backdrop,
                backdropBounds = hostBounds,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                containerColor = playlistMenuContainer,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(task.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (task.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LibraryActionButton("取消当前任务", CustomFontImportSession::cancelActive, primary = false)
                }
            }
        } else if (notice != null) {
            val alpha = remember { Animatable(1f) }
            LaunchedEffect(notice.id) {
                alpha.snapTo(1f)
                delay(if (notice.isError) 3_500L else 1_800L)
                alpha.animateTo(0f, musicMotion(300))
                if (CustomFontImportSession.notice?.id == notice.id) CustomFontImportSession.clearNotice()
            }
            MusicOneBackdropGlass(
                backdropLayer = backdrop,
                backdropBounds = hostBounds,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().graphicsLayer { this.alpha = alpha.value },
                shape = RoundedCornerShape(20.dp),
                containerColor = playlistMenuContainer,
            ) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(
                        notice.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notice.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
