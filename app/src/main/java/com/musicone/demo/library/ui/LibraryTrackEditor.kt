package com.musicone.demo

import androidx.compose.runtime.Composable

/** 编辑内容驻留原歌曲菜单内，不再另起表单窗口。 */
@Composable
internal fun LibraryTrackEditor(menu: QqPlaylistSongMenuState) {
    val ui = LocalMusicLibraryUi.current
    val track = menu.track ?: return
    menu.editForm?.let { form ->
        form.labels.forEachIndexed { index, label ->
            LibraryFormField(label, form.values[index].value, { form.values[index].value = it }, !menu.busy)
        }
        SongMenuRow("保存资料", !menu.busy, menu::submitEditor)
        SongMenuRow("选择封面", !menu.busy && !ui.busy) { ui.chooseCover(track) }
        SongMenuRow("恢复默认封面", !menu.busy && !ui.busy) { ui.restoreCover(track) }
    }
    SongMenuRow("返回", !menu.busy, menu::back)
}
