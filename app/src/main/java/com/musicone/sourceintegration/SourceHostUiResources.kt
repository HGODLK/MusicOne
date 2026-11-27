@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.musicone.sourceintegration

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalImageVectorCache
import androidx.compose.ui.platform.LocalResourceIdCache
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.ImageVectorCache
import androidx.compose.ui.res.ResourceIdCache

/** 切回宿主资源的 Context 和缓存给公共组件用，组件外面依然用音源包自己的资源。 */
@Composable
internal fun SourceHostUiResources(context: Context, content: @Composable () -> Unit) {
    // 与运行时 SourceResourceScope 一样绑定 Compose 1.11.4 的资源缓存 ABI。
    val resourceIds = remember(context) { ResourceIdCache() }
    val imageVectors = remember(context) { ImageVectorCache() }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalResources provides context.resources,
        LocalResourceIdCache provides resourceIds,
        LocalImageVectorCache provides imageVectors,
        content = content,
    )
}
