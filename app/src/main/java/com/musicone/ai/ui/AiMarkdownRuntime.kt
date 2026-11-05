package com.musicone

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.Code

private data class AiMarkdownPalette(val ink: Int, val link: Int, val background: Int, val density: Float)
private val aiMarkdownRuntimes = android.util.LruCache<AiMarkdownPalette, AiMarkdownPreparer>(4)
internal val LocalAiMarkdownPreparer = staticCompositionLocalOf<AiMarkdownPreparer?> { null }

private fun aiMarkdownRuntime(context: Context, palette: AiMarkdownPalette): AiMarkdownPreparer = synchronized(aiMarkdownRuntimes) {
    aiMarkdownRuntimes.get(palette) ?: run {
        val markwon = Markwon.builder(context.applicationContext).usePlugin(TablePlugin.create(context.applicationContext))
            .usePlugin(StrikethroughPlugin.create()).usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(palette.link).codeTextColor(palette.ink).codeBackgroundColor(palette.background)
                }
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(Code::class.java) { _, _ -> AiInlineCodeSpan(palette.ink, palette.background, palette.density) }
                }
            }).build()
        AiMarkdownPreparer(markwon, palette.ink, palette.link, palette.background, palette.density)
            .also { aiMarkdownRuntimes.put(palette, it) }
    }
}

/** 首次解析器构建放在后台；同主题复用一份引擎，不在每条历史及每块正文重复创建。 */
@Composable
internal fun AiMarkdownEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val palette = AiMarkdownPalette(colors.onSurface.toArgb(), colors.primary.toArgb(),
        colors.surfaceContainerHigh.toArgb(), LocalDensity.current.density)
    var runtime by remember(context, palette) { mutableStateOf(aiMarkdownRuntimes.get(palette)) }
    LaunchedEffect(context, palette) {
        if (runtime == null) runtime = withContext(Dispatchers.Default) { aiMarkdownRuntime(context, palette) }
    }
    val prepared = runtime
    if (prepared == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    } else CompositionLocalProvider(LocalAiMarkdownPreparer provides prepared, content = content)
}
