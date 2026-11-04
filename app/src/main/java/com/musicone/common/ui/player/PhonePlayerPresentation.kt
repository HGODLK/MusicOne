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

/** 方向与关联页返回时序独立保存，页面主组件只接线。 */
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
        // 监听方向继续保留给下次开页，隐藏播放页不能把普通页面和队列一起转向。
        rotationDegrees = if (isPhone && mounted) rotation else 0
    }

    fun relatedPagesChanged(open: Boolean) {
        if (open && rotationDegrees != 0) returningFromRelated = true
        relatedPages = open
    }

    /** 普通页点击直接使用当前观看方向展开，开合中仍只记录最新方向。 */
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

    /** 用户实际抓取后由本次手势决定去留，旧的按钮返回请求不再排队执行。 */
    fun cancelPendingDismissForGesture() {
        closingRequested = false
    }

    fun requestDismiss(action: () -> Unit) {
        closingRequested = true
        rotationJob?.cancel()
        settlingJob?.cancel()
        settlingJob = null
        if (!customPlayer && !flight.handoffActive && (rotationDegrees != 0 || bodyMoving || preparation.active)) {
            // 按钮返回也封存当前真实姿态，沿原收起曲线直接回到底部控件。
            // 不建立手势侧条位移，来源仍是物理底部的原播放控件。
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
            // 选择形态必须在开合结束后读取，不能沿用等待前的歌词开关状态。
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
                // 只锁定几何枢轴，正文继续按同一列宽独立推进。
                lyricsAnchor.releaseForMotion()
                lyricsHandoff.clear()
                animateRotation(target)
            } else {
                preparation.reset()
                lyricsAnchor.release()
                lyricsHandoff.clear()
                // 捕获失败只放弃新方向，原方向尚未结束的主体和按钮仍正常收尾。
                animateRotation(rotationDegrees)
            }
        }
    }

    /** 菜单暂停按钮朝向，释放后保留角度并清除暂停前旧动量；手势交接仍保留速度。 */
    fun menuMotionChanged(active: Boolean) {
        if (preparation.active) return
        // 菜单只暂停按钮角度，不能取消负责释放歌词阅读锁的整段转向任务。
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

    /** 切歌只补回已中断的收尾任务；仍在运行的转向及其速度完全保留。 */
    fun contentChanged() {
        // 收尾独立于按钮方向任务；后者等待菜单或被取消时不能扣住主体与歌词。
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
    // 普通页也记住最近的允许方向，点击时直接准备对应端点；监听仍受前台生命周期约束。
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
