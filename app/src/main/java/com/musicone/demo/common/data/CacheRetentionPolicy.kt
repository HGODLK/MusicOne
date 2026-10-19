package com.musicone.demo

internal enum class AudioCacheClearAction { KEEP, REMOVE, AFTER_PLAYBACK }

internal fun audioCacheClearAction(protected: Boolean, active: Boolean, all: Boolean): AudioCacheClearAction = when {
    all && active -> AudioCacheClearAction.AFTER_PLAYBACK
    active || (protected && !all) -> AudioCacheClearAction.KEEP
    else -> AudioCacheClearAction.REMOVE
}
