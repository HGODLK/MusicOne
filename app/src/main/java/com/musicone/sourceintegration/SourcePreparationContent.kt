package com.musicone.sourceintegration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.MusicOnePage
import com.musicone.MusicSource
import com.musicone.ReportPrimaryHeaderScroll

/** 仅占据音源内容区，导航、设置和本地资料库始终可以操作。 */
@Composable
internal fun SourcePreparationContent(source: MusicSource, page: MusicOnePage, bottomInset: Dp,
    error: String?, onChooseSource: () -> Unit) {
    val list = rememberLazyListState()
    ReportPrimaryHeaderScroll(page, list)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 96.dp, end = 20.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(source.label, style = MaterialTheme.typography.headlineSmall) }
        item { Text(error ?: "正在准备音源，完成后自动显示内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { OutlinedButton(onClick = onChooseSource) { Text("选择音源或使用本地音乐") } }
    }
}
