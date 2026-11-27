package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first

/** 管理手机播放页的横竖屏方向、转向动画及关联详情页返回状态，避免塞进页面主 Composable。 */
@Stable
internal class PhonePlayerPresentation(private val scope: CoroutineScope) {
    var rotationDegrees by mutableIntStateOf(0)
        private set
    val actionRotation = Animatable(0f)
    val turn = PhonePlayerTurn(scope)
    val preparation = PhonePlayerTurnPreparation()
    val lyricsLayout = LyricLayoutReadiness()
    val lyricsAnchor = PhoneLyricsReadingAnchor()
    val lyricsHandoff = PhoneLyricsTurnHandoff()
    val lyricsRotationGate = PhoneLyricsRotationGate()
    internal var controlsVisibility: PlayerControlsVisibilityState? = null
    internal var canStartRotation: (Int) -> Boolean = { true }
    private var requestedRotation: Int? = null
    private var rotationWaiting = false
    private var rotationRequestVersion = 0
    val flight = PhonePlayerFlightRotation(scope)
    val openingAnchors = PhonePlayerOpeningAnchors()
    val menuLocks = PhonePlayerMenuLocks(turn)
    val informationAnchors = PhonePlayerInformationAnchors()
    val portraitLyrics = mutableStateOf(false)
    // 同一次打开共享歌词选择，横竖切换保留；prepareOpen 统一清为关闭。
    val landscapeLyrics = portraitLyrics
    var latestRotation by mutableIntStateOf(0)
    var nativeOrientation by mutableStateOf(PhonePlayerNativeOrientation())
    val nativeRotation get() = PhonePlayerOrientationPolicy.playerRotation(nativeOrientation.rotationDegrees,
        PhonePlayerPreferences.disableLandscapePlayer)
    val nativeOpeningRotation get() = PhonePlayerOrientationPolicy.playerRotation(nativeOrientation.openingRotation,
        PhonePlayerPreferences.disableLandscapePlayer)
    val nativeRotationReady get() = PhonePlayerPreferences.disableLandscapePlayer || nativeOrientation.ready
    var customPlayer by mutableStateOf(false)
    var isPhone = false
    var relatedPages by mutableStateOf(false)
        private set
    var returningFromRelated = false
    var relatedNavigation: EntityNavigation? = null
    private var rotationJob: Job? = null
    private var actionJob: Job? = null
    private var closingRequested = false
    private val additionalMotions = mutableStateMapOf<String, Boolean>()
    private var actionVelocity = 0f
    private var settlingJob: Job? = null
    private var settlingGeneration = -1
    val pendingDismissActive get() = closingRequested
    private val bodyMotion = derivedStateOf { turn.hasVisibleMotion(BodyKeys) }
    val bodyMoving get() = bodyMotion.value
    val transitioning get() = preparation.active || turn.isTurning || additionalMotions.values.any { it }

    companion object { val BodyKeys = setOf("cover", "title", "artist", "favorite", "lyrics") }

    fun additionalMotion(key: String, moving: Boolean) { additionalMotions[key] = moving }

    suspend fun updateCustomPresentation(rotation: Int, mounted: Boolean) {
        reset()
        // 播放页关闭时强制归正为 0 度，防止影响到普通页面和播放队列；传感器方向仍留在后台供下次打开用。
        rotationDegrees = if (isPhone && mounted) rotation else 0
    }

    fun relatedPagesChanged(open: Boolean) {
        if (open && rotationDegrees != 0) returningFromRelated = true
        relatedPages = open
    }

    /** 准备打开播放页：按当前手机朝向初始化方向和共享锚点。 */
    fun prepareOpen() {
        if (!isPhone) return
        if (customPlayer) {
            closingRequested = false
            rotationDegrees = latestRotation
            return
        }
        rotationJob?.cancel()
        settlingJob?.cancel()
        settlingJob = null
        preparation.reset()
        lyricsAnchor.clear()
        lyricsHandoff.clear()
        menuLocks.reset()
        val target = nativeOpeningRotation
        turn.reset(target)
        openingAnchors.begin(target)
        closingRequested = false
        portraitLyrics.value = false
        rotationDegrees = target
        flight.prepare(rotationDegrees)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            actionVelocity = 0f
            actionRotation.snapTo(rotationDegrees.toFloat())
        }
    }

    /** 用户开始手动拖动时，取消之前排队的按钮关闭请求。 */
    fun cancelPendingDismissForGesture() {
        closingRequested = false
    }

    fun requestDismiss(action: () -> Unit) {
        closingRequested = true
        rotationJob?.cancel()
        settlingJob?.cancel()
        settlingJob = null
        if (!customPlayer && !flight.handoffActive && (rotationDegrees != 0 || bodyMoving || preparation.active)) {
            // 点击返回按钮时同样接管当前实际姿态，直接沿收起动画回到迷你播放条。
            flight.beginTurnDrag(turn)
            turn.releasePreparation()
            preparation.reset()
        }
        lyricsAnchor.release()
        action()
    }

    fun showRotation(target: Int) {
        if (rotationDegrees == target && !preparation.active) {
            if (rotationWaiting && requestedRotation != target) {
                rotationRequestVersion++
                rotationJob?.cancel()
                rotationWaiting = false
                requestedRotation = null
            }
            return
        }
        if (rotationWaiting && requestedRotation == target) return
        val version = ++rotationRequestVersion
        requestedRotation = target
        val previous = rotationJob
        previous?.cancel()
        rotationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.cancelAndJoin()
            rotationWaiting = true
            try { lyricsRotationGate.await { portraitLyrics.value } }
            finally { if (version == rotationRequestVersion) rotationWaiting = false }
            if (!canStartRotation(target) || rotationDegrees == target && !preparation.active) return@launch
            settlingJob?.cancel()
            settlingJob = null
            // 等歌词开合结束后再读一次当前是否开着歌词，不要用等待前的旧状态。
            val keys = setOf("cover", "title", "artist", "favorite", "play", "previous", "next",
                "quality", "mode", "elapsed", "remaining", "fixedActions") +
                if (portraitLyrics.value) setOf("lyrics") else if (target != 0) setOf("clock") else emptySet()
            val ready = preparation.prepare(turn, target, keys,
                contentReady = { "lyrics" !in keys || lyricsLayout.ready(turn.generation, target) &&
                    lyricsAnchor.ready(turn.generation, target) },
                contentGeometry = { if ("lyrics" in keys) lyricsLayout.geometry(turn.generation, target) else Unit },
                captureReading = { "lyrics" !in keys || lyricsAnchor.captureFrame() },
                prepareMotion = lyricsAnchor::pinForMotion) {
                controlsVisibility?.prepareRotation()
                openingAnchors.begin(target)
                rotationDegrees = target
            }
            if (ready) {
                turn.releasePreparation()
                preparation.release()
                // 释放阅读位置锁，只保留几何枢轴用于转向动画，歌词正文恢复正常播放滚动。
                lyricsAnchor.releaseForMotion()
                lyricsHandoff.clear()
                animateRotation(target)
            } else {
                preparation.reset()
                lyricsAnchor.release()
                lyricsHandoff.clear()
                // 准备失败就放弃切到新方向，让原方向还没播完的动画继续收尾。
                animateRotation(rotationDegrees)
            }
        }
    }

    /** 弹窗菜单打开时暂停按钮旋转动画并清零初速度，关闭后从当前角度继续。 */
    fun menuMotionChanged(active: Boolean) {
        if (preparation.active) return
        // 只取消按钮旋转，不要取消整个转向收尾任务（否则歌词阅读锁放不掉）。
        actionJob?.cancel()
        actionVelocity = 0f
        if (!active && rotationJob?.isActive != true &&
            (turn.isTurning || actionRotation.value != rotationDegrees.toFloat())) continueRotation()
    }

    private fun continueRotation() {
        val target = rotationDegrees
        rotationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animateRotation(target)
        }
    }

    /** 切歌重绑后检查是否需要补跑被中断的转向收尾，正在运行的动画不受影响。 */
    fun contentChanged() {
        // 主体转向收尾与按钮旋转分开，防止按钮被菜单暂停时卡住主体和歌词。
        ensureTurnSettlement()
        if (!preparation.active && rotationJob?.isActive != true &&
            (turn.isTurning || actionRotation.value != rotationDegrees.toFloat())) continueRotation()
    }

    private fun ensureTurnSettlement() {
        if (preparation.active || !turn.isTurning) return
        val generation = turn.generation
        if (settlingGeneration == generation && settlingJob?.isActive == true) return
        settlingJob?.cancel()
        settlingGeneration = generation
        settlingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turn.awaitSettled()
            if (turn.generation == generation && !turn.isTurning) {
                lyricsAnchor.release()
                lyricsHandoff.clear()
            }
        }
    }

    private suspend fun animateRotation(target: Int) {
        coroutineScope {
            launch {
                while (true) {
                    snapshotFlow { !menuLocks.active }.first { it }
                    val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                        if (ExperiencePreferences.options.reduceMotion) actionRotation.snapTo(target.toFloat())
                        else actionRotation.animateTo(target.toFloat(), musicSpring(stiffness = PHONE_PLAYER_TURN_STIFFNESS),
                            initialVelocity = actionVelocity) { actionVelocity = velocity }
                    }
                    actionJob = operation
                    operation.join()
                    if (!menuLocks.active && actionRotation.value == target.toFloat()) break
                }
                actionVelocity = 0f
            }
            ensureTurnSettlement()
            settlingJob?.join()
        }
    }

    suspend fun reset() {
        settlingJob?.cancel()
        settlingJob = null
        rotationJob?.cancel()
        rotationJob = null
        preparation.reset()
        lyricsAnchor.clear()
        lyricsHandoff.clear()
        menuLocks.reset()
        turn.reset()
        flight.complete()
        rotationDegrees = 0
        returningFromRelated = false
        closingRequested = false
        additionalMotions.clear()
        actionVelocity = 0f
        actionRotation.snapTo(0f)
    }
}

internal val LocalPhonePlayerPresentation = staticCompositionLocalOf<PhonePlayerPresentation?> { null }

@Composable
internal fun rememberPhonePlayerPresentation(expanded: Boolean, motion: PageMotion,
    queue: PlayerQueueState): PhonePlayerPresentation {
    // 在普通页面也持续监听手机朝向，点开播放页时就能直接按当前方向展开。
    val orientation = rememberPhonePlayerOrientation(true)
    val scope = rememberCoroutineScope()
    val presentation = remember(scope) { PhonePlayerPresentation(scope) }
    val nativeRotation = if (presentation.customPlayer) orientation.rotationDegrees else
        PhonePlayerOrientationPolicy.playerRotation(orientation.native.rotationDegrees, PhonePlayerPreferences.disableLandscapePlayer)
    val nativeRotationReady = presentation.customPlayer || PhonePlayerPreferences.disableLandscapePlayer || orientation.native.ready
    SideEffect {
        presentation.latestRotation = orientation.rotationDegrees
        presentation.nativeOrientation = orientation.native
        presentation.isPhone = orientation.isPhone
        presentation.canStartRotation = { requested ->
            presentation.nativeRotationReady && requested == presentation.nativeRotation && motion.phase == MotionPhase.SHOWN &&
                !presentation.pendingDismissActive && !presentation.relatedPages && !queue.visible && !presentation.menuLocks.active
        }
        motion.prepareSharedAnchors = if (orientation.isPhone && !presentation.customPlayer) presentation.openingAnchors::ready else null
    }
    LaunchedEffect(presentation.menuLocks.active) {
        presentation.menuMotionChanged(presentation.menuLocks.active)
    }
    LaunchedEffect(presentation.relatedPages) {
        if (presentation.relatedPages) queue.dismiss()
    }
    LaunchedEffect(orientation.isPhone, orientation.rotationDegrees, nativeRotation, nativeRotationReady, motion.phase,
        presentation.relatedPages, queue.visible, presentation.menuLocks.active, presentation.customPlayer) {
        when {
            presentation.customPlayer -> {
                presentation.updateCustomPresentation(orientation.rotationDegrees, motion.mounted)
            }
            !orientation.isPhone || !motion.mounted -> presentation.reset()
            !nativeRotationReady -> Unit
            presentation.relatedPages || queue.visible || presentation.menuLocks.active || motion.phase != MotionPhase.SHOWN || presentation.pendingDismissActive -> Unit
            else -> {
                if (presentation.returningFromRelated) {
                    if (nativeRotation == 0) delay(150)
                    presentation.returningFromRelated = false
                }
                presentation.showRotation(nativeRotation)
            }
        }
    }
    return presentation
}
