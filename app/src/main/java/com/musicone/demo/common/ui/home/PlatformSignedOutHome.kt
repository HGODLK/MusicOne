package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 未登录时一级页面不创建任何平台内容，也不会触发对应数据请求。 */
@Composable
internal fun PlatformSignedOutScreen(
    page: MusicOnePage,
    source: MusicSource,
    checking: Boolean,
    bottomInset: Dp,
    onChooseSource: () -> Unit,
) {
    val listState = rememberLazyListState()
    ReportPrimaryHeaderScroll(page, listState)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 82.dp, bottom = bottomInset + 18.dp),
    ) {
        item {
            Column(
                Modifier.fillParentMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                if (checking) {
                    CircularProgressIndicator()
                    Text(
                        "正在确认${source.label}登录状态…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "选择音源开始使用",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "登录后即可查看首页、搜索和收藏内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onChooseSource) { Text("选择音源并登录") }
                }
            }
        }
    }
}
