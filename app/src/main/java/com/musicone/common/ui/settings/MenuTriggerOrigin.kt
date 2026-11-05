package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 记录触发控件的位置与按下点，菜单从实际按下处展开；无按下点时退回控件中心。 */
@Stable
internal class MenuTriggerOrigin {
    var bounds by mutableStateOf(Rect.Zero)
        internal set
    internal var press: Offset? = null
    val origin: Offset get() = press ?: bounds.center
}

@Composable
internal fun rememberMenuTriggerOrigin(): MenuTriggerOrigin = remember { MenuTriggerOrigin() }

internal fun Modifier.menuTriggerOrigin(origin: MenuTriggerOrigin): Modifier =
    onGloballyPositioned { origin.bounds = it.boundsInRoot() }
        .pointerInput(origin) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                origin.press = origin.bounds.topLeft + down.position
            }
        }
