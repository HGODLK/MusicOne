package com.musicone.demo

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

internal fun <T> LazyListScope.searchResultItems(group: String, rows: List<T>, limit: Int, enabled: Boolean,
    folds: SearchResultFolds, key: (T) -> Any, content: @Composable LazyItemScope.(T) -> Unit) {
    itemsIndexed(rows, key = { _, row -> key(row) }) { index, row ->
        if (!enabled || index < limit) content(row) else {
            val itemScope = this
            val visible = index < folds.count(group, limit)
            var settledVisible by rememberSaveable { mutableStateOf(false) }
            val transition = remember { MutableTransitionState(settledVisible) }
            transition.targetState = visible
            LaunchedEffect(transition.currentState, transition.isIdle) {
                if (transition.isIdle) settledVisible = transition.currentState
            }
            AnimatedVisibility(transition,
                enter = expandVertically(musicMotion(320)) + fadeIn(musicMotion(240)),
                exit = shrinkVertically(musicMotion(320)) + fadeOut(musicMotion(240))) {
                itemScope.content(row)
            }
        }
    }
}
