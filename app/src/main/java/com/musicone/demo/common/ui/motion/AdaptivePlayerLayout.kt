package com.musicone.demo

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 平板双栏只用于足够宽的横屏；竖屏平板沿用手机单栏交互。 */
internal fun usesTabletLandscape(width: Dp, height: Dp): Boolean =
    width >= 700.dp && height >= 480.dp && width > height
