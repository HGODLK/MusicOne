package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal data class LyricTextGeometry(val origin: IntOffset, val size: IntSize)

internal data class LyricLayoutStamp(val owner: Any, val generation: Int, val rotation: Int,
    val width: Int, val height: Int, val line: Int, val translation: Boolean, val leading: Int = 0,
    val pane: Any? = null, val mount: Int = 0, val preparation: Any? = null)

/** 就绪资格绑定实际视口和可见文字，只在换窗、改向或换行时更新。 */
@Stable
internal class LyricLayoutReadiness {
    // 准备任务逐帧读取，无需让绘制确认反过来使文字图层失效。
    private var expected: LyricLayoutStamp? = null
    private var original: LyricLayoutStamp? = null
    private var translated: LyricLayoutStamp? = null
    private var originalGeometry: LyricTextGeometry? = null
    private var translationGeometry: LyricTextGeometry? = null
    private var liveGeometry: (() -> Any?)? = null

    fun expect(stamp: LyricLayoutStamp) {
        if (expected == stamp) return
        expected = stamp
        original = null
        translated = null
        originalGeometry = null
        translationGeometry = null
        liveGeometry = null
    }

    fun clear(owner: Any) { if (expected?.owner === owner) {
        expected = null; original = null; translated = null; liveGeometry = null
    } }

    fun observeGeometry(stamp: LyricLayoutStamp, read: () -> Any?) {
        if (expected == stamp) liveGeometry = read
    }

    fun drawn(stamp: LyricLayoutStamp, translation: Boolean, viewportWidth: Int, viewportHeight: Int,
        paragraphWidth: Int, expectedParagraphWidth: Int, clipReady: Boolean = true,
        geometry: LyricTextGeometry? = null) {
        if (expected != stamp) return
        val valid = stamp.width > 0 && stamp.height > 0 &&
            viewportWidth == stamp.width && viewportHeight == stamp.height &&
            paragraphWidth > 0 && paragraphWidth == expectedParagraphWidth && clipReady
        // 晚到的错误裁剪也撤销旧确认，三帧稳定检查才能反映当前画面。
        if (translation) translated = stamp.takeIf { valid } else original = stamp.takeIf { valid }
        if (translation) translationGeometry = geometry.takeIf { valid }
        else originalGeometry = geometry.takeIf { valid }
    }

    fun ready(generation: Int, rotation: Int): Boolean = expected?.let {
        it.generation == generation && it.rotation == rotation && original == it &&
            (!it.translation || translated == it)
    } == true

    fun geometry(generation: Int, rotation: Int): Any? =
        if (!ready(generation, rotation)) null else liveGeometry?.invoke()
            ?: if (liveGeometry == null) listOf(expected, originalGeometry, translationGeometry) else null
}

internal class LyricLayoutProof(val readiness: LyricLayoutReadiness, val stamp: LyricLayoutStamp,
    private val pane: LyricWindowPane, private val drawingRange: LyricDrawingRangeProof? = null,
    private val onPrepared: () -> Unit = {}) {
    private val textCoordinates = arrayOfNulls<LayoutCoordinates>(2)
    private var notified = false
    private fun geometry(translation: Boolean): LyricTextGeometry? {
        val host = pane.listHost.coordinates() ?: return null
        val text = textCoordinates[if (translation) 1 else 0] ?: return null
        if (!host.isAttached || !text.isAttached) return null
        return runCatching {
            val point = host.localPositionOf(text, Offset.Zero)
            LyricTextGeometry(IntOffset(point.x.roundToInt(), point.y.roundToInt()), text.size)
        }.getOrNull()
    }
    private val readGeometry: () -> Any? = {
        val host = pane.listHost.coordinates()
        val clipReady = lyricTextClipReady(host, textCoordinates[0]) &&
            (!stamp.translation || lyricTextClipReady(host, textCoordinates[1]))
        val original = geometry(false)
        val translated = if (stamp.translation) geometry(true) else null
        val range = drawingRange?.geometry()
        if (!clipReady || original == null || stamp.translation && translated == null || drawingRange != null && range == null) null
        else listOf(stamp, original, translated, range)
    }
    fun placed(index: Int, translation: Boolean, coordinates: LayoutCoordinates) {
        drawingRange?.placed(index, translation, coordinates)
        if (index == stamp.line) textCoordinates[if (translation) 1 else 0] = coordinates
    }
    fun drawn(index: Int, translation: Boolean, paragraphWidth: Int, expectedWidth: Int) {
        drawingRange?.drawn(index, translation, paragraphWidth > 0 && paragraphWidth == expectedWidth)
        if (index != stamp.line || !pane.listHost.ready) return
        val viewport = pane.listState.layoutInfo.viewportSize
        val host = pane.listHost.coordinates()
        val text = textCoordinates[if (translation) 1 else 0]
        val geometry = if (host?.isAttached == true && text?.isAttached == true) runCatching {
            val position = host.localPositionOf(text, Offset.Zero)
            LyricTextGeometry(IntOffset(position.x.roundToInt(), position.y.roundToInt()), text.size)
        }.getOrNull() else null
        readiness.drawn(stamp, translation, viewport.width, viewport.height, paragraphWidth, expectedWidth,
            lyricTextClipReady(host, text), geometry)
        readiness.observeGeometry(stamp, readGeometry)
        val valid = readiness.ready(stamp.generation, stamp.rotation) && readGeometry() != null
        if (valid && !notified) { notified = true; onPrepared() }
        if (!valid) notified = false
    }
}

/** 放在文字明暗门控里面，只有真正重放字形时才能确认该次目标。 */
internal fun Modifier.lyricLayoutProof(proof: LyricLayoutProof?, index: Int, translation: Boolean,
    layout: () -> TextLayoutResult?, companionLayout: () -> TextLayoutResult? = { null },
    expectedWidth: () -> Int): Modifier =
    if (proof == null) this else onGloballyPositioned { proof.placed(index, translation, it) }.drawWithContent {
        drawContent()
        layout()?.let { proof.drawn(index, translation, it.layoutInput.constraints.maxWidth, expectedWidth()) }
        // 长段落可能把另一块文字推到屏外；确认其新排版即可，不强求屏外字形也入屏绘制。
        companionLayout()?.let { proof.drawn(index, !translation, it.layoutInput.constraints.maxWidth, expectedWidth()) }
    }
