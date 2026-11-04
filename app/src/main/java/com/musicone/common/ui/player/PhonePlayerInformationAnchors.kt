package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 保存真实竖屏位置供后续旋转动效使用，不用于横屏静态排版。 */
internal class PhonePlayerInformationAnchors {
    val centersY = mutableStateMapOf<String, Float>()
}

internal fun Modifier.phonePortraitInformationAnchor(key: String): Modifier = composed {
    val presentation = LocalPhonePlayerPresentation.current
    val visible = LocalPlayerVisible.current
    val rotation = LocalPhonePlayerRotation.current
    onGloballyPositioned { coordinates ->
        if (presentation != null && visible && rotation == 0 && !presentation.turn.isTurning) {
            presentation.informationAnchors.centersY[key] = coordinates.boundsInRoot().center.y
        }
    }
}
