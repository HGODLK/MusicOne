package com.musicone.sourceintegration

import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.SourceRoute
import com.musicone.source.sdk.SourceSupplementalPageContext
import com.musicone.source.sdk.SourcePlayerUiContext
import kotlinx.coroutines.*

/** 外部组合使用独立重组器，包页面异常只替换本区域，宿主导航继续可用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceExternalSurface(instance: SourceInstance, route: SourceRoute, modifier: Modifier = Modifier,
    mode: String = "content", onExit: () -> Unit = {}, interactive: Boolean = true,
    supplementalPage: SourceSupplementalPageContext? = null, onFailure: ((String) -> Unit)? = null,
    playerUi: SourcePlayerUiContext? = null, onReady: (() -> Unit)? = null,
    rootIdentity: SourceRootIdentity? = null) {
    val context = LocalContext.current
    val host = remember(context) { SourceApplicationHost.get(context) }
    val latestExit by rememberUpdatedState(onExit)
    DisposableEffect(host, instance, interactive) {
        val target = if (interactive) host.exitTargets.register(instance.entry.descriptor.id) { latestExit() } else null
        onDispose { target?.close() }
    }
    var failure by remember(instance) { mutableStateOf<String?>(null) }
    val latestRoute by rememberUpdatedState(route)
    val latestInteractive by rememberUpdatedState(interactive)
    val latestSupplementalPage by rememberUpdatedState(supplementalPage)
    val latestPlayerUi by rememberUpdatedState(playerUi)
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onFailure)
    val colors by rememberUpdatedState(MaterialTheme.colorScheme)
    val typography by rememberUpdatedState(MaterialTheme.typography)
    val shapes by rememberUpdatedState(MaterialTheme.shapes)
    // 独立重组器仍继承当前宿主能力；资源 Context 由 SourceResourceScope 在包入口内覆盖。
    val inheritedLocals by rememberUpdatedState(currentCompositionLocalContext)
    val parentMotionOffset = com.musicone.LocalMotionRootOffset.current
    var surfaceMotionOffset by remember(instance, mode) { mutableStateOf(Offset.Zero) }
    val rootCoordinates = remember(instance, rootIdentity) { rootIdentity?.let { SourceRootCoordinates() } }
    // 消费量在测量阶段直接传递，避免重组延后一帧，也不替子树预消费 IME 或导航栏。
    val consumedInsets = remember(instance, mode) { MutableWindowInsets() }
    // 外部资源Context属于包；系统选择器与返回分发仍使用当前Activity的公开所有者。
    val resultOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
    val backOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
    val parentLifecycle = LocalLifecycleOwner.current
    val surfaceLifecycle = remember(instance, mode, parentLifecycle) { SourceSurfaceLifecycle(parentLifecycle) }
    SideEffect { surfaceLifecycle.setInteractive(interactive) }
    DisposableEffect(surfaceLifecycle) { onDispose { surfaceLifecycle.close() } }
    val surface = remember(instance, mode, surfaceLifecycle, rootIdentity) {
        IsolatedSourceView(context, { failure = it.message ?: "音源页面加载失败" },
            reportCancellation = mode == "player", ready = { latestReady?.invoke() }) {
            CompositionLocalProvider(inheritedLocals) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner,
                LocalOnBackPressedDispatcherOwner provides backOwner,
                com.musicone.LocalMotionRootOffset provides surfaceMotionOffset,
                com.musicone.LocalMotionRootOffsetReader provides rootCoordinates?.read,
                LocalLifecycleOwner provides surfaceLifecycle,
                LocalSourcePublicSurface provides SourcePublicSurfaceContext(
                    mode in setOf("content", "supplemental-page") && latestRoute.arguments["active"] != "false", latestInteractive, surfaceMotionOffset,
                    consumedInsets = consumedInsets, rootIdentity = rootIdentity, coordinates = rootCoordinates)) {
                val focus = LocalFocusManager.current
                SideEffect { if (!latestInteractive) focus.clearFocus(force = true) }
                MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography) {
                    when (mode) {
                        "player" -> instance.PlayerUi(requireNotNull(latestPlayerUi))
                        "supplemental-page" -> instance.SupplementalPage(requireNotNull(latestSupplementalPage))
                        "authentication" -> instance.AuthenticationContent()
                        "settings" -> instance.SettingsContent()
                        else -> instance.Content(latestRoute)
                    }
                }
            }
            }
        }
    }
    DisposableEffect(surface) { onDispose { surface.close() } }
    if (failure == null) key(surface) {
        // AndroidView 的 factory 只在节点创建时调用；切换音源必须同时替换真实 View，不能保留已关闭的旧页面。
        AndroidView(factory = { surface }, modifier = modifier.fillMaxSize()
            .onConsumedWindowInsetsChanged { consumedInsets.insets = it }
            .onGloballyPositioned {
            val offset = it.boundsInRoot().topLeft + parentMotionOffset
            if (rootCoordinates != null) rootCoordinates.offset = offset else surfaceMotionOffset = offset
        },
            update = { it.setInteractive(interactive) })
    }
    else if (onFailure != null) {
        LaunchedEffect(failure) { latestFailure?.invoke(failure.orEmpty()) }
    } else Surface(modifier.fillMaxSize()) {
        // 内容页的宿主返回栏覆盖在上方；错误提示同样留出栏位，避免标题被返回按钮遮住。
        Column(Modifier.padding(24.dp).padding(top = if (mode == "content") 64.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${instance.entry.descriptor.name}页面暂不可用", style = MaterialTheme.typography.titleLarge)
            Text(failure.orEmpty())
            Button(onClick = onExit) { Text("返回") }
        }
    }
}

private class IsolatedSourceView(context: Context, private val failed: (Throwable) -> Unit,
    private val reportCancellation: Boolean = false,
    private val ready: () -> Unit = {},
    content: @Composable () -> Unit) : FrameLayout(context), AutoCloseable {
    private var broken = false
    private var closed = false
    private var contentStarted = false
    private var readyReported = false
    private val inputBoundary = SourcePlayerInputBoundary(::fail)
    private val accessibilityBoundary = SourcePlayerAccessibilityBoundary(inputBoundary) { interactive && !broken && !closed }
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.CurrentThread + CoroutineExceptionHandler { _, error -> fail(error) })
    private val recomposer = Recomposer(scope.coroutineContext)
    private val compose = ComposeView(context)
    private val startContent = Runnable {
        if (!closed && !broken && !contentStarted && isAttachedToWindow) {
            contentStarted = true
            // 首次组合会在setContent内同步执行，必须脱离宿主attach调用栈并直接捕获。
            try { compose.setContent(content) } catch (error: Throwable) { fail(error) }
        }
    }
    private var interactive = true
    fun setInteractive(value: Boolean) {
        interactive = value
        descendantFocusability = if (value) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        if (!value) clearFocus()
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!interactive) return true
        if (!reportCancellation) return super.dispatchTouchEvent(event)
        if (broken || closed) return true
        return inputBoundary.call(true) { super.dispatchTouchEvent(event) }
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!reportCancellation) return super.dispatchKeyEvent(event)
        if (!interactive || broken || closed) return true
        return inputBoundary.call(true) { super.dispatchKeyEvent(event) }
    }
    override fun dispatchKeyEventPreIme(event: android.view.KeyEvent): Boolean {
        if (!reportCancellation) return super.dispatchKeyEventPreIme(event)
        if (!interactive || broken || closed) return true
        return inputBoundary.call(true) { super.dispatchKeyEventPreIme(event) }
    }
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (!reportCancellation) return super.dispatchGenericMotionEvent(event)
        if (!interactive || broken || closed) return true
        return inputBoundary.call(true) { super.dispatchGenericMotionEvent(event) }
    }
    override fun dispatchHoverEvent(event: android.view.MotionEvent): Boolean {
        if (!reportCancellation) return super.dispatchHoverEvent(event)
        if (!interactive || broken || closed) return true
        return inputBoundary.call(true) { super.dispatchHoverEvent(event) }
    }
    init {
        compose.setParentCompositionContext(recomposer)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!closed && !broken && !contentStarted) post(startContent)
    }
    private fun fail(error: Throwable) {
        if (broken || closed || error is CancellationException && !reportCancellation) return
        broken = true
        post { if (!closed) { close(); failed(error) } }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        try { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        catch (error: Throwable) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec)); fail(error) }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            super.onLayout(changed, left, top, right, bottom)
            // 子 ComposeView 已创建真实 AndroidComposeView，再装饰它的虚拟无障碍节点入口。
            if (reportCancellation && !broken && !closed) accessibilityBoundary.install(compose)
            // 开页准备时宿主透明度为零，必须以真实布局就绪释放准备，不能等待被跳过的绘制。
            if (!broken && !closed && contentStarted && !readyReported && compose.width > 0 && compose.height > 0) {
                readyReported = true
                post { if (!closed && !broken) ready() }
            }
        } catch (error: Throwable) { fail(error) }
    }
    override fun dispatchDraw(canvas: Canvas) {
        try {
            super.dispatchDraw(canvas)
        } catch (error: Throwable) { fail(error) }
    }
    override fun close() {
        if (closed) return
        closed = true
        accessibilityBoundary.clear()
        removeCallbacks(startContent)
        recomposer.cancel()
        scope.cancel()
        runCatching { compose.disposeComposition() }
    }
}
