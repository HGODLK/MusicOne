package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable internal fun NeteaseSearchContent(owner: NeteasePackageState, actions: NeteaseTrackActions) {
    if (owner.account == null) { NeteaseSignedOut(owner, "登录后搜索网易云音乐"); return }
    Column(Modifier.fillMaxSize().padding(top = 82.dp)) {
        OutlinedTextField(owner.searchQuery, { owner.searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("搜索歌曲、歌手") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { owner.search(owner.searchQuery) }),
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { owner.search("") }) { Icon(Icons.Default.Close, "清空搜索") } })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !owner.loading && owner.searchQuery.isNotBlank(), onClick = { owner.search(owner.searchQuery) }) { Text("搜索") }
        }
        if (owner.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            items(owner.searchTracks, key = { it.id }) { track -> NeteaseTrackRow(owner, actions, track, onClick = { owner.play(owner.searchTracks, owner.searchTracks.indexOf(track)) }) }
            if (owner.searchTracks.isEmpty() && !owner.loading) item { Text(owner.message ?: "输入关键词搜索音乐", Modifier.padding(20.dp)) }
            owner.message?.takeIf { owner.searchTracks.isNotEmpty() }?.let { text -> item { Text(text, Modifier.padding(20.dp)) } }
            item { Spacer(Modifier.height((owner.bottomInset + 20).dp)) }
        }
    }
}
