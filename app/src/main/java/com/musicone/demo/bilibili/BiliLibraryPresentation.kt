package com.musicone.demo

internal fun BiliLibraryUiState.visiblePage(): BiliLibraryPage =
    if (account == null) BiliLibraryPage.ACCOUNT else page

internal fun BiliFavFolder.asPlaylist(tracks: List<MusicTrack> = emptyList()): MusicPlaylist =
    MusicPlaylist("bili-folder-$id", MusicSource.BILIBILI, title, "$mediaCount 个视频", "", mediaCount,
        0L, 0L, "", tracks, artworkUrl = cover.takeIf(String::isNotBlank))

/** 浏览、播放与多选复用同一列表快照，分 P 和音频地址仍按实际播放解析。 */
internal fun BiliFolderSnapshot.asPlaylist(folder: BiliFavFolder): MusicPlaylist {
    val tracks = videos.filter { !it.isInvalid && it.bvid.isNotBlank() }.map(BiliTrackMapper::toFallbackTrack)
    require(tracks.isNotEmpty()) { "收藏夹中暂无可播放内容" }
    return folder.asPlaylist(tracks)
}
