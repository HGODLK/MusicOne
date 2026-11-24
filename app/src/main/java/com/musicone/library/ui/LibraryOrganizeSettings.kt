package com.musicone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 手动整理一次；提示里说明新建了多少歌单。 */
internal fun MusicLibraryUiController.organize(kinds: List<AutoPlaylistKind>) = work("正在整理…") {
    message = store.organizeLocalLibrary(kinds).summary()
}

@Composable
internal fun LibraryOrganizeSettings(ui: MusicLibraryUiController) {
    val preference = remember(ui.context) { LibraryAutoOrganizePreference(ui.context) }
    var automatic by remember { mutableStateOf(preference.enabled) }
    LibrarySettingsGroup("自动整理") {
        Text("按文件里的专辑和歌手信息新建本地歌单，不改动已有歌单。少于 2 首的不建；删掉的自动歌单和移出的歌曲不会再加回来。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryActionButton("按专辑整理", { ui.organize(listOf(AutoPlaylistKind.ALBUM)) }, Modifier.weight(1f),
                enabled = !ui.busy, primary = false)
            LibraryActionButton("按歌手整理", { ui.organize(listOf(AutoPlaylistKind.ARTIST)) }, Modifier.weight(1f),
                enabled = !ui.busy, primary = false)
        }
        Row(Modifier.fillMaxWidth().toggleable(automatic, role = Role.Switch) {
            automatic = it
            preference.enabled = it
        }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("导入后自动整理", style = MaterialTheme.typography.titleSmall)
                Text("每次导入或刷新目录后，自动按专辑和歌手整理", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MusicOneSettingsSwitch(automatic)
        }
    }
}
