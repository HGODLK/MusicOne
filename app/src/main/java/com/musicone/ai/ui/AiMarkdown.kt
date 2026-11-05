package com.musicone

import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableBlock

private data class AiMarkdownKey(val text: String, val ink: Int, val link: Int, val background: Int, val density: Float)

/** 跨列表回收保留已排版正文，按文本体积限制缓存，不持有页面或 View。 */
private val aiMarkdownBlocks = object : android.util.LruCache<AiMarkdownKey, List<AiMarkdownBlock>>(400_000) {
    override fun sizeOf(key: AiMarkdownKey, value: List<AiMarkdownBlock>): Int = key.text.length.coerceAtLeast(1)
}

internal data class AiMarkdownBlock(val text: CharSequence, val columns: Int = 0)

/** 与正文使用同一主题键预热，历史消息在富文本就绪后才交付布局。 */
internal class AiMarkdownPreparer internal constructor(
    internal val markwon: Markwon, private val ink: Int, private val link: Int, private val background: Int, internal val density: Float,
) {
    private fun texts(message: AiChatMessageUi): List<String> = if (message.role == "user") emptyList() else
        listOfNotNull(message.text.takeIf(String::isNotBlank),
            message.result?.takeIf { it.isNotBlank() && !message.isNativeReceipt && message.receipt == null })

    fun ready(messages: List<AiChatMessageUi>): Boolean = messages.all { message ->
        texts(message).all { aiMarkdownBlocks.get(AiMarkdownKey(it, ink, link, background, density)) != null }
    }

    suspend fun prepare(messages: List<AiChatMessageUi>) = withContext(Dispatchers.Default) {
        messages.forEach { message -> texts(message).forEach { render(it) } }
    }

    @Synchronized internal fun render(text: String): List<AiMarkdownBlock> {
        val key = AiMarkdownKey(text, ink, link, background, density)
        aiMarkdownBlocks.get(key)?.let { return it }
        val document = markwon.parse(text)
        val result = mutableListOf<AiMarkdownBlock>()
        var node = document.firstChild
        while (node != null) {
            val current = node
            val columns = if (current is TableBlock) {
                var count = 0
                var cell = current.firstChild?.firstChild?.firstChild
                while (cell != null) { count++; cell = cell.next }
                count.coerceAtLeast(1)
            } else 0
            result += AiMarkdownBlock(markwon.render(current), columns)
            node = current.next
        }
        aiMarkdownBlocks.put(key, result)
        return result
    }
}

@Composable
internal fun rememberAiMarkdownPreparer(): AiMarkdownPreparer {
    return checkNotNull(LocalAiMarkdownPreparer.current)
}

/** 使用完整 Markdown 解析器；表格单独横向滚动，不撑宽整条消息。 */
@Composable
internal fun AiMarkdown(text: String, modifier: Modifier = Modifier, small: Boolean = false, streaming: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface.toArgb()
    val link = colors.primary.toArgb()
    val codeBackground = colors.surfaceContainerHigh.toArgb()
    val size = (if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge).fontSize.value
    val preparer = rememberAiMarkdownPreparer()
    val markwon = preparer.markwon
    val cacheKey = remember(text, ink, link, codeBackground, preparer.density) { AiMarkdownKey(text, ink, link, codeBackground, preparer.density) }
    val initialBlocks = remember(cacheKey) {
        aiMarkdownBlocks.get(cacheKey) ?: if (streaming)
            listOf(AiMarkdownBlock(android.text.SpannableString(text))) else emptyList()
    }
    val blocks by produceState(initialBlocks, cacheKey, markwon) {
        aiMarkdownBlocks.get(cacheKey)?.let { value = it; return@produceState }
        // 流式尾部解析不阻塞主线程，保留上一份富文本直至新结果就绪。
        value = withContext(Dispatchers.Default) { preparer.render(text) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocks.isEmpty() && text.isNotBlank()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        blocks.forEach { block ->
            if (block.columns > 0) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tableWidth = maxOf(maxWidth, (block.columns * 128).dp)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    AiMarkdownText(block.text, markwon, ink, link, size, Modifier.width(tableWidth))
                }
            } else AiMarkdownText(block.text, markwon, ink, link, size, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AiMarkdownText(text: CharSequence, markwon: Markwon, ink: Int, link: Int, size: Float, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context -> TextView(context).apply {
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setTextIsSelectable(true)
        movementMethod = TableAwareMovementMethod.create()
    } }, update = { view ->
        view.setTextColor(ink)
        view.setLinkTextColor(link)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        // 无关重组不重复 setText，保留选择并避免 TextView 再次排版。
        if (view.tag !== text) {
            markwon.setParsedMarkdown(view, text as android.text.Spanned)
            view.tag = text
        }
    })
}
