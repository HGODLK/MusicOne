package com.musicone.demo

internal enum class CacheClearMode(val title: String, val description: String) {
    ORDINARY("清除未受保护缓存", "保留受保护歌曲和正在使用的音频，不删除歌单、收藏或本地音乐原文件。"),
    OUTSIDE_PLAYLISTS("清理歌单之外的缓存", "保留所有歌单中的歌曲缓存，只清理歌单之外的音频；正在使用的缓存会在释放后清理。"),
    ALL("清除所有缓存", "包含受保护音频，不删除歌单、收藏或本地音乐原文件。正在使用的缓存会在释放后清理。"),
}

/** 未知身份不能证明不属于歌单，保留到能够确认归属时再处理。 */
internal fun outsidePlaylistCacheKeys(resources: Map<String, String?>, members: Set<String>): List<String> =
    resources.filter { (_, identity) -> identity != null && identity !in members }.keys.toList()
