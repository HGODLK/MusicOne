package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

/** 等待只观察开合，不接管其任务或消耗转向准备的三秒预算。 */
internal class PhoneLyricsRotationGate {
    private var owner: Any? = null
    private var motion by mutableStateOf<PhoneLyricsMotion?>(null)
    fun attach(owner: Any, motion: PhoneLyricsMotion) { this.owner = owner; this.motion = motion }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; motion = null } }
    fun ready(visible: Boolean): Boolean = motion?.settledForRotation(visible) ?: !visible
    suspend fun await(visible: () -> Boolean) { snapshotFlow { ready(visible()) }.first { it } }
}

internal fun PhoneLyricsMotion.settledForRotation(visible: Boolean): Boolean {
    val target = if (visible) 1f else 0f
    return !dragging && openingAlignment == null && !lyrics.isRunning && !header.isRunning &&
        !entranceAlpha.isRunning && lyricsProgress == target && headerProgress == target && entranceAlpha.value == 1f
}
