package com.musicone.demo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal class AppUpdateLifecycle(private val model: AppUpdateViewModel) : DefaultLifecycleObserver {
    // 只在应用重新进入可见阶段检查；权限弹窗结束等单纯恢复不会重新建立提醒窗口。
    override fun onStart(owner: LifecycleOwner) = model.openApplication()
    override fun onResume(owner: LifecycleOwner) = model.setForeground(true)
    override fun onPause(owner: LifecycleOwner) = model.setForeground(false)
}

private val LocalAppUpdates = staticCompositionLocalOf<AppUpdateViewModel?> { null }

@Composable
internal fun AppUpdateHost(model: AppUpdateViewModel, content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CompositionLocalProvider(LocalAppUpdates provides model) {
        val backdropLayer = rememberGraphicsLayer()
        var bounds by remember { mutableStateOf(Rect.Zero) }
        val prompt = state.prompt
        val visible = prompt != null && model.canRenderPrompt()
        val visibility = remember { MutableTransitionState(false) }
        visibility.targetState = visible
        val mounted = visibility.currentState || visibility.targetState
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .playerQualityBackdropSnapshot(backdropLayer, mounted),
            ) { content() }

            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(musicMotion(240)),
                exit = fadeOut(musicMotion(200)),
            ) {
                // 内容归属动画生命周期，清空业务提示后仍保留到退场结束。
                var retained by remember { mutableStateOf(prompt) }
                if (prompt != null) SideEffect { retained = prompt }
                val release = prompt ?: retained
                BackHandler(enabled = visible) { model.dismissPrompt() }
                if (visible) SideEffect { model.recordPromptShown() }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .48f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            if (visible) model.dismissPrompt()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (release != null) AppUpdatePrompt(
                        release = release,
                        installedVersion = state.installedVersion,
                        backdropLayer = backdropLayer,
                        bounds = bounds,
                        onDismiss = { if (visible) model.dismissPrompt() },
                        onDownload = { if (visible) openUpdateDownload(context, release) },
                        onGithub = { if (visible) openUpdateLink(context, MUSIC_ONE_GITHUB_PAGE) },
                        modifier = Modifier.animateEnterExit(
                            enter = scaleIn(musicMotion(280), initialScale = .88f) +
                                slideInVertically(musicMotion(280)) { it / 16 },
                            exit = scaleOut(musicMotion(220), targetScale = .92f) +
                                slideOutVertically(musicMotion(220)) { it / 16 },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun VersionUpdateSettings() {
    val model = LocalAppUpdates.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val available = state.available
    Column {
        Text("版本更新", style = MaterialTheme.typography.titleLarge)
        ListItem(
            headlineContent = {
                Text(when {
                    available != null -> "${available.version.label} 获取下载"
                    state.checking -> "正在检查更新…"
                    state.error != null -> "检查更新"
                    state.checked -> "已是最新版"
                    else -> "检查更新"
                })
            },
            supportingContent = {
                Text(if (available == null && state.error != null) state.error.orEmpty()
                    else "当前版本 ${state.installedVersion}")
            },
            trailingContent = {
                if (state.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("›")
            },
            modifier = Modifier.fillMaxWidth().clickable(
                enabled = !state.checking || available != null,
                role = Role.Button,
            ) {
                if (available != null) model.showPromptManually(available) else model.checkManually()
            },
        )
    }
}

private fun openUpdateDownload(context: Context, release: MusicOneRelease) {
    openUpdateLink(context, updateDownloadGatewayUrl(release.downloadUrl))
}

private fun openUpdateLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可打开下载链接的浏览器", Toast.LENGTH_SHORT).show()
    }
}

