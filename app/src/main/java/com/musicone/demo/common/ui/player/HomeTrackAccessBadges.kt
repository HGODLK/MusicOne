package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf

/** 只控制歌曲标题的会员与付费角标，首页外默认沿用原有展示。 */
internal val LocalTrackAccessBadgesVisible = staticCompositionLocalOf { true }
