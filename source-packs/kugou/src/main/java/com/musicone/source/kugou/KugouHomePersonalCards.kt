package com.musicone.source.kugou

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourceComponentScope
import com.musicone.source.sdk.SourceLoadedHomeComponents

/** 两张卡直接复用公共日推和公共封面歌单卡，附加信息始终留在封面内。 */
@Composable
internal fun KugouHomePersonalCards(
    state: KugouHomeState, components: SourceComponentScope, daily: SourceCollection,
    recent: SourceCollection, heightDp: Float, narrow: Boolean, onDaily: () -> Unit,
    onRecent: () -> Unit, onPlayRecent: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(heightDp.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val loadedHome = components as? SourceLoadedHomeComponents
            if (loadedHome == null) components.InlineMessage("请更新宿主以使用完整每日推荐动效")
            else loadedHome.DailyCard(daily,
                state.daily.takeIf { state.owner?.accountId == daily.identity.accountId }.orEmpty(), state.dailyDate?.takeLast(2),
                state.loadingDaily && !state.dailyLoaded, state.dailyError.takeUnless { state.dailyLoaded },
                Modifier.fillMaxSize(), onDaily)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // 手机保留公共大标题字号，按词分行，避免“放”单独挤到第二行。
            val display = if (narrow) recent.copy(title = "最近\n播放") else recent
            components.FeaturedCard(display, heightDp, compact = true, modifier = Modifier.fillMaxSize(),
                onPlay = onPlayRecent, onClick = onRecent)
        }
    }
}
