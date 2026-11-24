package com.musicone

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import kotlin.math.roundToLong

private val ttmlOffset = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|f|t)")
private val ttmlClock = Regex("(\\d+):([0-5]\\d):([0-5]\\d)(?:(\\.\\d+)|:([0-9]+)(?:\\.([0-9]+))?)?")
private val xmlDeclaration = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
private val ttmlWhitespace = Regex("[\\r\\n]+[ \\t]*")

internal fun parseLocalTtml(raw: String): List<TimedLyric> = runCatching {
    if (xmlDeclaration.containsMatchIn(raw)) return emptyList()
    val root = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(InputSource(StringReader(raw))).documentElement
    if (root.localName != "tt") return emptyList()
    TtmlTimeline(root).lines()
}.getOrDefault(emptyList())

private class TtmlTimeline(private val root: Element) {
    private val clockRate = root.attr("frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
    private val multiplier = root.attr("frameRateMultiplier")?.split(Regex("\\s+"))?.mapNotNull(String::toDoubleOrNull)
    private val frames = multiplier?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let { clockRate * it[0] / it[1] } ?: clockRate
    private val subFrames = root.attr("subFrameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
    private val ticks = root.attr("tickRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: if (root.attr("frameRate") == null) 1.0 else frames * subFrames
    // Apple Music／AMLL 将各层时钟写成歌曲绝对时间，标准 TTML 则相对于父容器。
    private val absolute = root.attr("timing") != null || root.lookupNamespaceURI("itunes") != null || root.lookupNamespaceURI("amll") != null

    private data class Timing(val start: Long, val end: Long?)
    private data class Piece(val text: String, val time: Timing? = null, val reading: String? = null)

    fun lines(): List<TimedLyric> {
        val head = root.children().firstOrNull { it.localName == "head" }
        fun sidecars(section: String): Map<String, List<Element>> = head?.descendants(section).orEmpty().flatMap { container ->
            container.descendants("text").mapNotNull { row -> row.attr("for")?.removePrefix("#")?.let { key -> key to row } }
        }.groupBy({ it.first }, { it.second })
        val translations = sidecars("translation").mapValues { (_, rows) -> rows.map { it.textContent.trim() }.filter(String::isNotBlank).distinct().joinToString("\n") }
        val readings = sidecars("transliteration")
        val body = root.children().firstOrNull { it.localName == "body" } ?: return emptyList()
        val result = mutableListOf<TimedLyric>()
        fun visit(element: Element, parent: Timing) {
            val time = timing(element, parent)
            if (element.localName == "p") {
                val key = element.attr("key") ?: element.attr("id")
                row(element, time, translations[key], readings[key].orEmpty())?.let(result::add)
                element.children().filter { it.isBackground() }.forEach { background ->
                    row(background, timing(background, time), null, emptyList())?.let(result::add)
                }
            } else element.children().forEach { visit(it, time) }
        }
        visit(body, Timing(0L, null))
        return result.sortedBy(TimedLyric::timeMs)
    }

    private fun row(element: Element, time: Timing, translation: String?, readings: List<Element>): TimedLyric? {
        val pieces = mutableListOf<Piece>()
        val translated = mutableListOf<String>()
        val transliterated = mutableListOf<String>()
        fun walk(node: Node, parent: Timing, word: Timing?) {
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val raw = node.nodeValue.orEmpty()
                val text = if (raw.isBlank() && '\n' in raw) "" else ttmlWhitespace.replace(raw, " ")
                if (text.isNotEmpty()) pieces += Piece(text, word)
            }
            if (node !is Element || node.isBackground()) return
            val role = node.attr("role")
            when (role) {
                "x-translation" -> { translated += node.textContent.trim(); return }
                "x-roman", "x-transliteration" -> { transliterated += node.textContent.trim(); return }
            }
            if (node.localName == "br") { pieces += Piece("\n", word); return }
            val own = timing(node, parent)
            val next = if (node.attr("begin") != null) own else word
            val rubyStyle = node.attr("ruby")
            if (rubyStyle == "container" || node.localName == "ruby") {
                val children = node.children()
                val base = children.filter { it.attr("ruby") in setOf("base", "baseContainer") || it.localName == "rb" }
                    .joinToString("") { it.textContent }.ifEmpty {
                        node.nodes().filterNot { it is Element && (it.localName in setOf("rt", "rp") || it.attr("ruby") in setOf("text", "textContainer", "delimiter")) }
                            .joinToString("") { it.textContent }
                    }
                val ruby = children.filter { it.attr("ruby") in setOf("text", "textContainer") || it.localName == "rt" }
                    .joinToString(" ") { it.textContent.trim() }
                val baseTiming = children.firstOrNull { it.attr("begin") != null && (it.attr("ruby") == "base" || it.localName == "rb") }
                    ?.let { timing(it, own) }
                pieces += Piece(base, baseTiming ?: next, ruby)
                return
            }
            node.nodes().forEach { walk(it, own, next) }
        }
        element.nodes().forEach { walk(it, time, null) }
        val joined = pieces.joinToString("") { it.text }
        val text = joined.trim().takeIf(String::isNotEmpty) ?: return null
        val start = if (element.attr("begin") != null) time.start else pieces.firstNotNullOfOrNull { it.time?.start } ?: time.start
        val words = mutableListOf<LyricWord>()
        if (pieces.any { it.time != null }) {
            var prefix = ""
            for ((index, piece) in pieces.withIndex()) {
                val span = piece.time
                if (span == null) {
                    if (words.isEmpty()) prefix += piece.text
                    else words[words.lastIndex] = words.last().let { it.copy(text = it.text + piece.text) }
                    continue
                }
                val end = span.end ?: pieces.subList(index + 1, pieces.size).firstNotNullOfOrNull { it.time?.start } ?: time.end ?: span.start
                val previous = words.lastOrNull()
                if (previous != null && previous.startMs == span.start && previous.endMs == end) {
                    words[words.lastIndex] = previous.copy(text = previous.text + piece.text)
                } else words += LyricWord(span.start, maxOf(span.start, end), prefix + piece.text)
                prefix = ""
            }
        }
        val timedReadings = readings.map { row -> row.descendants("span").mapNotNull { span ->
            val startMs = millis(span.attr("begin")) ?: return@mapNotNull null
            val endMs = millis(span.attr("end")) ?: return@mapNotNull null
            (startMs to endMs) to span.textContent.trim()
        }.toMap() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val ruby = pieces.joinToString("") { localReading(it.text, it.reading) ?: it.text }.trim().takeIf { lyricRubyAnchors(text, it) != null }
            ?: words.joinToString("") { localReading(it.text, timedReadings[it.startMs to it.endMs]) ?: it.text }
                .trim().takeIf { lyricRubyAnchors(text, it) != null }
            ?: localReading(text, (readings.filter { it.descendants("span").isEmpty() }.map { it.textContent } + transliterated)
                .firstOrNull(String::isNotBlank))
        val translatedText = (listOfNotNull(translation) + translated).filter { it.isNotBlank() && it != text }.distinct().joinToString("\n").ifEmpty { null }
        return TimedLyric(start, text, translatedText, words, transliteration = ruby)
    }

    private fun timing(element: Element, parent: Timing): Timing {
        val base = if (absolute) 0L else parent.start
        val start = millis(element.attr("begin"))?.let { lyricEnd(base, it) } ?: parent.start
        val end = millis(element.attr("end"))?.let { lyricEnd(base, it) }
            ?: millis(element.attr("dur"))?.let { lyricEnd(start, it) } ?: parent.end
        return Timing(start, end)
    }

    private fun millis(value: String?): Long? {
        val text = value ?: return null
        if (absolute && Regex("\\d+(?:\\.\\d+)?").matches(text)) {
            return (text.toDouble() * 1000.0).takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.roundToLong()
        }
        val offset = ttmlOffset.matchEntire(text)
        if (offset != null) {
            val amount = offset.groupValues[1].toDouble()
            val unit = when (offset.groupValues[2]) { "h" -> 3_600_000.0; "m" -> 60_000.0; "s" -> 1000.0; "f" -> 1000.0 / frames; "t" -> 1000.0 / ticks; else -> 1.0 }
            return (amount * unit).takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.roundToLong()
        }
        val standard = ttmlClock.matchEntire(text) ?: return lyricClockMillis(text)
        val (h, m, s, fraction, frame, subFrame) = standard.destructured
        val time = (h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble() + (fraction.toDoubleOrNull() ?: 0.0) +
            ((frame.toDoubleOrNull() ?: 0.0) + (subFrame.toDoubleOrNull() ?: 0.0) / subFrames) / frames) * 1000
        return time.takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.roundToLong()
    }
}

private fun Element.attr(name: String): String? = (0 until attributes.length).asSequence().map(attributes::item)
    .firstOrNull { it.localName == name }?.nodeValue?.trim()?.ifEmpty { null }
private fun Node.nodes(): List<Node> = (0 until childNodes.length).map(childNodes::item)
private fun Element.children(): List<Element> = nodes().filterIsInstance<Element>()
private fun Element.descendants(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list ->
    (0 until list.length).mapNotNull { list.item(it) as? Element }
}
private fun Element.isBackground(): Boolean = attr("role") == "x-bg"
