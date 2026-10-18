package com.musicone.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun LibrarySongMenuActions(menu: QqPlaylistSongMenuState, playlist: MusicPlaylist, playerMenu: Boolean = false, hasLyrics: Boolean = false) {
    val ui = LocalMusicLibraryUi.current
    val track = menu.track ?: return
    if (playerMenu && track.source.supportsOnlineLyricOffset) {
        if (onlineLyricOffsetActionsVisible(menu.fromLyricsTitle, hasLyrics)) LyricOffsetMenuActions(menu, track)
        return
    }
    if (playerMenu && (track.source == MusicSource.LOCAL || track.source == MusicSource.BILIBILI)) {
        CompositionLocalProvider(LocalSongMenuWorking provides (ui.busy || menu.busy)) {
            if (hasLyrics) {
                SongMenuRow("修改歌词", !ui.busy && !menu.busy) {
                    menu.dismiss()
                    ui.chooseLyrics(track)
                }
                LyricOffsetMenuActions(menu, track)
            } else {
                SongMenuRow("添加歌词", !ui.busy && !menu.busy) {
                    menu.dismiss()
                    ui.chooseLyrics(track)
                }
            }
        }
        return
    }
    PlaylistAddMenuRow("添加到综合歌单", ui.snapshot.unified, menu)
    if (!track.source.supportsLibraryEditing) return
    if (track.source == MusicSource.LOCAL) SongMenuRow("重新关联音频文件", !ui.busy) { menu.dismiss(); ui.chooseRelinkFile(track) }
    if (track.source == MusicSource.LOCAL) SongMenuRow("从本地音乐移除", !ui.busy && !menu.busy) {
        menu.dismiss()
        ui.work("正在移除…") { ui.store.removeLocalSong(track.id); ui.message = "已从本地音乐移除，原文件保留" }
    }
    if (track.source == MusicSource.LOCAL || track.source == MusicSource.BILIBILI) {
        val lyricRevision = ui.snapshot.lyricRevision
        val hasManualLyrics = remember(track.id, lyricRevision) {
            ui.store.lyricAsset(track.id).first != null
        }
        SongMenuRow(if (hasManualLyrics) "修改歌词" else "添加歌词", !ui.busy && !menu.busy) {
            menu.dismiss()
            ui.chooseLyrics(track)
        }
        if (hasManualLyrics) SongMenuRow("移除歌词", !ui.busy && !menu.busy) {
            menu.dismiss()
            ui.work("正在移除手动歌词…") {
                ui.store.setLyrics(track.id, null)
                ui.message = "已移除手动歌词，将使用内嵌或默认歌词"
            }
        }
    }
    SongMenuRow("编辑歌曲资料", !ui.busy && !menu.busy, menu::openEditor)
}

/** 复用同一套快捷偏移操作，处理期间只禁用点击，不让文字短暂变灰。 */
@Composable
private fun LyricOffsetMenuActions(menu: QqPlaylistSongMenuState, track: MusicTrack) {
    val ui = LocalMusicLibraryUi.current
    CompositionLocalProvider(LocalSongMenuWorking provides (ui.busy || menu.busy)) {
        SongMenuRow("歌词提前 0.1 秒", !ui.busy && !menu.busy) { ui.shift(track, -100) }
        SongMenuRow("歌词延后 0.1 秒", !ui.busy && !menu.busy) { ui.shift(track, 100) }
        SongMenuRow("输入歌词偏移／归零", !ui.busy && !menu.busy) { menu.dismiss(); ui.offset(track) }
    }
}
