package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PlatformMyLayout(account: MusicAccount?, bottomInset: Dp) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportPrimaryHeaderScroll(MusicOnePage.MY, listState)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 86.dp, bottom = bottomInset + 18.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            AccountAvatar(account = account, size = 68.dp)
        }
    }
}
