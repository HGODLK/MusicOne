package com.musicone

/** 暂存后台正在播放的轻量状态，页面重建时直接恢复，避免重新初始化播放器。 */
internal class RetainedPlaybackState {
    private var snapshot: MusicOneUiState? = null

    fun remember(state: MusicOneUiState, playerTrackId: String?) {
        if (state.currentTrack != null && state.currentTrack.id == playerTrackId) snapshot = state
    }

    fun restore(source: MusicSource, playerTrackId: String?, base: MusicOneUiState, playing: Boolean): MusicOneUiState? {
        val saved = snapshot?.takeIf {
            it.currentTrack != null && it.currentTrack.id == playerTrackId
        } ?: return null
        return saved.copy(
            page = base.page, playerExpanded = base.playerExpanded, isPlaying = playing,
            qualityLoading = false, qualityChanging = false, qualityTarget = null,
            radioLoading = false, playbackMessage = null,
        )
    }

    fun clear() { snapshot = null }
}

internal val retainedPlaybackSession = RetainedPlaybackState()
