package com.musicone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MainSourceHotSearch(state: MainSourceSearchState, model: MainSourceSearchViewModel) {
    if (!state.supportsHotSearch) return
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("热搜", style = MaterialTheme.typography.labelLarge)
            if (state.hotLoading) Text("加载中…", style = MaterialTheme.typography.labelSmall)
            if (state.hotError != null) TextButton(onClick = model::loadHotSearch) { Text("重试") }
        }
        state.hotError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.fillMaxWidth()) {
            items(state.hotSearches, key = { it }) { word ->
                Text(word, Modifier.heightIn(min = 48.dp).clickable { model.submit(word) }.padding(12.dp))
            }
        }
    }
}
