package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首批歌曲尚未返回时保留推荐货架，手机和平板复用相似推荐的列数与骨架。 */
@Composable
internal fun QqMusicFeedLoadingShelf() {
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics {
        stateDescription = "正在加载歌曲推荐"
    }) {
        val columns = qqSimilarRecommendationColumns(maxWidth.value)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("歌曲推荐", fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
            QqSimilarLoadingGrid(columns)
        }
    }
}
