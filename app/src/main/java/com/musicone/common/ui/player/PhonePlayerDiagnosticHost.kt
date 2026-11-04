package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** 仅在方向、歌词请求和开合阶段变化后短时采样，不改变原动画的帧时钟。 */
@Composable
internal fun PhonePlayerDiagnosticHost(motion: PageMotion, lyricsMotion: PhoneLyricsMotion,
    presentation: PhonePlayerPresentation?, rotation: Int, lyrics: Boolean) {
    if (!PlayerMotionDiagnostics.enabled) return
    LaunchedEffect(rotation, lyrics, motion.phase) {
        PlayerMotionDiagnostics.event("player-request") {
            "rotation=$rotation lyrics=$lyrics phase=${motion.phase} wantsOpen=${motion.wantsOpen}"
        }
        repeat(30) {
            PlayerMotionDiagnostics.sample("player-state") {
                "rotation=$rotation lyrics=$lyrics phase=${motion.phase} page=${motion.value} " +
                    "lyricProgress=${lyricsMotion.lyricsProgress} header=${lyricsMotion.headerProgress} " +
                    "clock={${presentation?.turn?.diagnosticState("clock")}} " +
                    "lyric={${presentation?.turn?.diagnosticState("lyrics")}}"
            }
            delay(100)
        }
    }
}
