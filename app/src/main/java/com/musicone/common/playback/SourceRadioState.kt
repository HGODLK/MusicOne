package com.musicone

internal fun MusicOneUiState.withSourceRadioEvent(event: SourceRadioEvent): MusicOneUiState = when (event) {
    is SourceRadioEvent.Mode -> if (event.active) copy(radioActive = true, shuffle = false, repeatMode = RepeatMode.ALL)
        else copy(radioActive = false, radioLoading = false)
    is SourceRadioEvent.Loading -> copy(radioLoading = true, playbackMessage = null)
    is SourceRadioEvent.Started -> copy(radioActive = true, radioLoading = false,
        shuffle = false, repeatMode = RepeatMode.ALL)
    is SourceRadioEvent.Appended -> copy(queue = event.queue, radioLoading = false)
    is SourceRadioEvent.Failed -> copy(radioActive = if (event.initial) false else radioActive,
        radioLoading = false, playbackMessage = event.message)
}
