@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.musicone.source.runtime

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

// 共享组件读取宿主资源，包私有资源使用独立 packageId；资源路径与矢量缓存仍按实例隔离。
// Compose 1.11.4 的路径缓存只按整数 ID 索引，不能让多个 Resources/Theme 共用旧缓存。
// 此适配器绑定已校验的精确 Compose 版本；升级共享运行时前必须重新核对此处内部 ABI。
@Composable
internal fun SourceResourceScope(context: Context, content: @Composable () -> Unit) {
    val resourceIds = remember(context) { ResourceIdCache() }
    val imageVectors = remember(context) { ImageVectorCache() }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalResources provides context.resources,
        LocalResourceIdCache provides resourceIds,
        LocalImageVectorCache provides imageVectors,
        content = content
    )
}
