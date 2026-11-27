package com.musicone

import android.content.Context
import androidx.compose.runtime.*
import com.musicone.source.runtime.SourceInstance

/** 在设置层缓存各补充音源的页面状态，切去别的设置页再回来时保留输入内容和浏览位置。 */
internal class SupplementalSourceModels {
    private val models = mutableMapOf<String, Pair<SourceInstance, SupplementalSourceModel>>()

    fun obtain(context: Context, instance: SourceInstance): SupplementalSourceModel {
        val id = instance.entry.descriptor.id
        val previous = models[id]
        if (previous?.first === instance) return previous.second
        previous?.second?.close()
        return SupplementalSourceModel(context.applicationContext, instance).also { models[id] = instance to it }
    }

    fun close() { models.values.forEach { it.second.close() }; models.clear() }
}

@Composable
internal fun rememberSupplementalSourceModels(): SupplementalSourceModels {
    val models = remember { SupplementalSourceModels() }
    DisposableEffect(models) { onDispose { models.close() } }
    return models
}
