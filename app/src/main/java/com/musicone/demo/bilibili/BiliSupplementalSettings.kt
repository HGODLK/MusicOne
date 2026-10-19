package com.musicone.demo

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** 补充音源沿用设置的层级过渡，各级列表独立保留滚动位置。 */
@Composable
internal fun BiliSupplementalSettings(bottomInset: Dp = 0.dp, onBack: () -> Unit,
    onLocalBackChange: ((() -> Unit)?) -> Unit, viewModel: BiliLibraryViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val latestBack by rememberUpdatedState(onBack)
    var folderBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val back: () -> Unit = remember(viewModel) { {
        if ((viewModel.state.value.visiblePage() != BiliLibraryPage.FOLDER || folderBack?.invoke() != true) &&
            !viewModel.backPage()) latestBack()
    } }
    SideEffect { onLocalBackChange(back) }
    LaunchedEffect(viewModel) { viewModel.onPageEnter() }
    DisposableEffect(viewModel) {
        onDispose { onLocalBackChange(null); viewModel.onPageExit() }
    }
    val scrolls = BiliLibraryPage.entries.associateWith { rememberLazyListState() }
    val homeScroll = rememberLazyGridState()
    val foldersScroll = rememberLazyGridState()
    val visiblePage = state.visiblePage()
    // 沿用已有封面交叉渐变策略，让收藏夹、视频封面与头像异步更新时保持连续。
    CompositionLocalProvider(LocalArtworkEntrancePolicy provides LocalArtworkEntrancePolicy.current.copy(fadeArtwork = true)) {
    // 表单与验证层共享同一尺寸，避免父 Column 将验证层排到可见区域之外。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 页头固定高度，长标题与加载反馈不再改变整页内容的起点。
            Box(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                SettingsHeader(when (visiblePage) {
                    BiliLibraryPage.HOME -> "补充音源"
                    BiliLibraryPage.ACCOUNT -> if (state.account == null) "登录哔哩哔哩" else "B站账号"
                    BiliLibraryPage.LINK -> "链接导入"
                    BiliLibraryPage.FOLDERS -> "音乐收藏夹"
                    BiliLibraryPage.FOLDER -> state.selectedFolder?.title ?: "收藏夹"
                    BiliLibraryPage.VIDEO -> "选择分 P"
                }, back, singleLine = true)
            }
            AnimatedContent(visiblePage, Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart, transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                ((fadeIn(musicMotion(320)) + slideInHorizontally(musicMotion(360)) { direction * it / 16 }) togetherWith
                    (fadeOut(musicMotion(220)) + slideOutHorizontally(musicMotion(280)) { -direction * it / 16 })).using(null)
            }, label = "补充音源层级衔接") { animatedPage ->
                // 退出登录的当帧也只绘制登录页，不让出场中的旧页面继续暴露收藏内容。
                val page = if (state.account == null) BiliLibraryPage.ACCOUNT else animatedPage
                if (page == BiliLibraryPage.HOME || page == BiliLibraryPage.FOLDERS) {
                    BiliLibraryHome(state, viewModel, if (page == BiliLibraryPage.HOME) homeScroll else foldersScroll,
                        bottomInset, foldersOnly = page == BiliLibraryPage.FOLDERS)
                } else if (page == BiliLibraryPage.FOLDER) {
                    key(state.account?.mid, state.selectedFolder?.id) {
                        BiliFolderScreen(state, viewModel, bottomInset) { folderBack = it }
                    }
                } else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val maxContentWidth = if (page == BiliLibraryPage.ACCOUNT) 560.dp else 1120.dp
                    val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
                    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxSize(), state = scrolls.getValue(page),
                        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (page != BiliLibraryPage.ACCOUNT || state.loginMethod != PlatformLoginMethod.SMS) {
                            state.error?.let { error -> item("error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                            state.message?.let { message -> item("message") { Text(message) } }
                        }
                        when (page) {
                            BiliLibraryPage.ACCOUNT -> item {
                                LibrarySettingsGroup(if (state.account == null) "哔哩哔哩" else "账号信息") { BiliAuthSection(state, viewModel) }
                            }
                            BiliLibraryPage.LINK -> item { BiliLinkEntry(state, viewModel) }
                            BiliLibraryPage.VIDEO -> item { BiliLinkSection(state, viewModel, showInput = false) }
                        }
                    }
                }
            }
        }
        state.geetestChallenge?.let { challenge ->
            BiliGeetestOverlay(challenge,
                onSuccess = { viewModel.onGeetestResolved(challenge, it) },
                onDismiss = { viewModel.dismissGeetest(challenge) },
                onFailure = { viewModel.failGeetest(challenge, it) })
        }
    }
    }
}
