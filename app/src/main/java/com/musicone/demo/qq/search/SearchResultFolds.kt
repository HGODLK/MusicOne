package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal class SearchResultFolds(private val increments: MutableState<List<String>>) {
    fun count(key: String, limit: Int) = limit + increments.value.count { it == key } * 10
    fun more(key: String) { increments.value = increments.value + key }
    fun collapse(key: String) { increments.value = increments.value.filterNot { it == key } }
}

@Composable
internal fun rememberSearchResultFolds(): SearchResultFolds {
    val increments = rememberSaveable { mutableStateOf(emptyList<String>()) }
    return remember(increments) { SearchResultFolds(increments) }
}

internal fun LazyListScope.searchFoldButton(key: String, count: Int, limit: Int, enabled: Boolean, folds: SearchResultFolds) {
    if (enabled && count > limit) item("fold-$key") {
        Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = musicMotion(260), placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220)),
            horizontalArrangement = Arrangement.Center) {
            if (folds.count(key, limit) < count) TextButton(onClick = { folds.more(key) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("更多") }
            if (folds.count(key, limit) > limit) TextButton(onClick = { folds.collapse(key) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("收起") }
        }
    }
}
