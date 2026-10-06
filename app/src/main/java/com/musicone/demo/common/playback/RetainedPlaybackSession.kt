package com.musicone.demo

/** 只保存正在由服务播放的轻量状态；页面重建时接管会话，不重设音源。 */
internal class RetainedPlaybackState {
    private var snapshot: MusicOneUiState? = null

    fun remember(state: MusicOneUiState, playerTrackId: String?) {
        if (state.currentTrack != null && state.currentTrack.id == playerTrackId) snapshot = state
    }

    fun restore(source: MusicSource, playerTrackId: String?, base: MusicOneUiState, playing: Boolean): MusicOneUiState? {
        val saved = snapshot?.takeIf {
            it.currentTrack?.source == source && it.currentTrack.id == playerTrackId
        } ?: return null
        return saved.copy(
            page = base.page, playerExpanded = base.playerExpanded, isPlaying = playing,
            qualityLoading = false, qualityChanging = false, qualityTarget = null,
            qqRadioLoading = false, playbackMessage = null,
        )
    }

    fun clear() { snapshot = null }
}

internal val retainedPlaybackSession = RetainedPlaybackState()
