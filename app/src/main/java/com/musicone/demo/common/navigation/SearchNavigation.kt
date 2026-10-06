package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class SearchNavigation {
    var opened by mutableStateOf(false)
        private set

    fun open() { opened = true }
    fun close() { opened = false }
}

@Composable
internal fun rememberSearchNavigation(playerMounted: Boolean): SearchNavigation {
    val navigation = remember { SearchNavigation() }
    BackHandler(enabled = navigation.opened && !playerMounted) { navigation.close() }
    return navigation
}
