package com.musicone

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

internal data class PhoneLyricsViewport(val landscape: Boolean = false,
    val top: Int = 0, val bottom: Int = 0, val horizontalPadding: Dp = 0.dp,
    val controlsHeight: () -> Float = { 0f }, val controlsHidden: () -> Float = { 0f },
    val reveal: () -> Float = { 1f }, val windowOffset: (() -> Float)? = null,
    val protection: (() -> LyricBlurProtection)? = null, val glass: PlayerLyricsTopGlass? = null,
    val residency: LyricResidency = LyricResidency.None)

/** 两种朝向保留同一修饰节点顺序，只有视口参数及原遮罩表现发生变化。 */
@Composable
internal fun Modifier.phoneLyricsViewport(value: PhoneLyricsViewport): Modifier {
    val turn = LocalPhonePlayerTurn.current
    val drawing = LocalPhoneLyricsDrawingWindow.current
    val backdropTurn = rememberPhoneLyricsBackdropTurn(turn)
    val fadeTurn = rememberPhoneLyricsFadeTurn(turn)
    SideEffect { if (value.landscape && turn?.orientationChanging != true) backdropTurn?.clear() }
    SideEffect { if (!value.landscape && turn?.orientationChanging != true) fadeTurn?.clear() }
    val retainBackdrop = turn?.orientationChanging == true && backdropTurn?.retained == true
    return layout { measurable, constraints ->
        val extension = value.top + value.bottom + drawing.top + drawing.bottom
        val child = measurable.measure(constraints.offset(vertical = extension))
        layout(child.width, (child.height - extension).coerceAtLeast(0)) {
            child.place(0, -value.top - drawing.top)
        }
    }.playerControlsBackdrop(value.controlsHeight, value.controlsHidden, value.reveal,
        bottomExtension = value.bottom, topExtension = value.top, topBuffer = value.top,
        windowMotionOffset = value.windowOffset, focusProtection = value.protection, topGlass = value.glass,
        prepareClosedSource = { turn?.isTurning != true }, enabled = !value.landscape || retainBackdrop,
        drawingWindow = drawing, backdropTurn = backdropTurn, landscape = value.landscape,
        orientationChanging = { turn?.orientationChanging == true },
        readingPivot = {
            val pose = turn?.layoutPose("lyrics")
            drawing.top + value.top + (pose?.let { it.size.height * it.pivotFraction.y } ?: 0f)
        })
        .tabletLyricsTopFade(fadeBottom = true, singlePass = true,
            enabled = value.landscape || turn?.orientationChanging == true,
            stableInsets = true, drawingWindow = drawing, fadeTurn = fadeTurn,
            landscape = value.landscape, orientationChanging = { turn?.orientationChanging == true },
            windowMotionOffset = value.windowOffset,
            readingPivot = {
                val pose = turn?.layoutPose("lyrics")
                drawing.top + value.top + (pose?.let { it.size.height * it.pivotFraction.y } ?: 0f)
            })
        .lyricResidentRefresh(value.residency).padding(horizontal = value.horizontalPadding)
}
