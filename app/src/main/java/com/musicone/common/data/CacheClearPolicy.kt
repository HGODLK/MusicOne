package com.musicone

internal enum class CacheClearMode(val title: String, val description: String) {
    ORDINARY("清除未受保护缓存", "保留受保护歌曲和正在使用的音频，不删除歌单、收藏或本地音乐原文件。"),
    OUTSIDE_PLAYLISTS("清理歌单之外的缓存", "保留所有歌单中的歌曲缓存，只清理歌单之外的音频；正在使用的缓存会在释放后清理。"),
    ALL("清除所有缓存", "包含受保护音频，不删除歌单、收藏或本地音乐原文件。正在使用的缓存会在释放后清理。"),
}

/** 拿不到归属信息的缓存不能随便当成歌单外文件删掉，先保留。 */
internal fun outsidePlaylistCacheKeys(resources: Map<String, String?>, members: Set<String>): List<String> =
    resources.filter { (_, identity) -> identity != null && identity !in members }.keys.toList()
