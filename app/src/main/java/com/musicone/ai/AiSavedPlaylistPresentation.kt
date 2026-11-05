package com.musicone

/** 正式本地歌单不沿用临时推荐的简介和 AI 标识，已有歌单资料不受影响。 */
internal fun aiReplaceSavedPlaylist(messages: List<AiChatMessageUi>, saved: MusicPlaylist): List<AiChatMessageUi> {
    val members = saved.tracks.map { it.source.id to it.id }.toSet()
    return messages.map { message ->
        val temporary = message.playlist
        if (temporary != null && (temporary.id.startsWith("ai-temporary:") || temporary.id.startsWith("ai-turn-")) &&
            temporary.tracks.map { it.source.id to it.id }.toSet() == members) message.copy(playlist = saved)
        else message
    }
}
