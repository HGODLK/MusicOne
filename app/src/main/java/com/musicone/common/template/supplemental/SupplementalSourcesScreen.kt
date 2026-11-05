package com.musicone

import androidx.compose.animation.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceKind
import com.musicone.source.sdk.SourceRoute
import com.musicone.source.sdk.SourceSupplementalPage
import com.musicone.source.sdk.supplementalPageProviderOrNull
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.SourceExternalSurface

/** 补充入口来自已激活描述符，新增音源不需要再添加固定平台分支。 */
@Composable
internal fun SupplementalSourcesScreen(bottomInset: Dp = 0.dp, onBack: () -> Unit,
    onLocalBackChange: ((() -> Unit)?) -> Unit, onManageSources: () -> Unit,
    initialSourceId: String? = null, models: SupplementalSourceModels = rememberSupplementalSourceModels(),
    entryRevision: Long = 0L) {
    val context = LocalContext.current
    val controller = remember(context) { SourcePackageController.get(context) }
    val installed by controller.installed.collectAsState()
    val startup by controller.startup.collectAsState()
    val sources = remember(installed) { controller.available(SourceKind.SUPPLEMENTAL).filter { supplementalPageAvailable(it.entry, SourceSupplementalPage.HOME) } }
    val pages = rememberSaveableStateHolder()
    var selectedId by key(entryRevision) { rememberSaveable { mutableStateOf(initialSourceId) } }
    val instance = sources.firstOrNull { it.entry.descriptor.id == selectedId }
    val latestBack by rememberUpdatedState(onBack)
    val closeSource = { selectedId = null; Unit }
    if (!startup.initializing && selectedId != null && instance == null) {
        LaunchedEffect(selectedId, installed) { selectedId = null }
    }
    val latestSelected by rememberUpdatedState(selectedId)
    AnimatedContent(selectedId, transitionSpec = {
        settingsPageTransform(targetState != null)
    }, label = "补充音源入口层级衔接") { displayed ->
        pages.SaveableStateProvider(displayed?.let { "source:$it" } ?: "supplemental-list") {
        val displayedInstance = sources.firstOrNull { it.entry.descriptor.id == displayed }
        val updateBack: ((() -> Unit)?) -> Unit = { handler ->
            if (latestSelected == displayed) onLocalBackChange(handler)
        }
        if (displayedInstance != null) {
            if (displayedInstance.entry.supplementalPageProviderOrNull() != null || !displayedInstance.entry.descriptor.useCustomPages) {
                SupplementalSourceScreen(displayedInstance.entry.descriptor.id, bottomInset, closeSource, updateBack,
                    models.obtain(context, displayedInstance))
            } else {
                val back: () -> Unit = { if (!displayedInstance.onBack()) closeSource() }
                SideEffect { updateBack(back) }
                DisposableEffect(displayedInstance) { onDispose { updateBack(null) } }
                Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                    SourceExternalSurface(displayedInstance, SourceRoute("home", mapOf("bottomInsetDp" to bottomInset.value.toString(), "active" to (latestSelected == displayed).toString())),
                        onExit = closeSource, interactive = latestSelected == displayed)
                }
            }
        } else {
        SideEffect { updateBack { latestBack() } }
        DisposableEffect(Unit) { onDispose { updateBack(null) } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Box(Modifier.padding(vertical = 20.dp)) { SettingsHeader("补充音源", onBack) } }
            items(sources, key = { it.entry.descriptor.id }) { source ->
                val descriptor = source.entry.descriptor
                LibrarySettingsGroup {
                    SupplementalSourceChoice(descriptor.name,
                        source.entry.authentication?.currentAccount()?.account?.displayName ?: if (descriptor.requiresLogin) "尚未登录" else "无需登录",
                        descriptor.presentation.pageColorArgb ?: MusicSource.fromId(descriptor.id).accent) {
                        selectedId = descriptor.id
                    }
                }
            }
            if (sources.isEmpty()) item { MainSourceInlineMessage(if (startup.initializing) "正在准备补充音源，完成后自动显示" else
                startup.error ?: "暂无已激活的补充音源，可导入补充音源包后重启使用。") }
            item { OutlinedButton(onClick = onManageSources) { Text("管理音源包") } }
        }
        }
    }
    }
}

/** 与主音源选择行一致：行首显示音源主题色小圆点。 */
@Composable
private fun SupplementalSourceChoice(title: String, subtitle: String, accent: Long, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color(accent),
            modifier = Modifier.padding(start = 12.dp).size(12.dp)) {}
        Box(Modifier.weight(1f)) { LibraryActionRow(title, subtitle, onClick = onClick) }
    }
}
