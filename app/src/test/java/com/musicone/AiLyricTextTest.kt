package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLyricTextTest {
    @Test fun untimedLyricsAreSpreadOverDurationAndMarkedEstimated() {
        val draft = aiLyricDraft("第一句\n\n第二句\n第三句\n第四句", 120_000)
        assertTrue(draft.estimated)
        assertEquals(listOf(0L, 30_000L, 60_000L, 90_000L), draft.lines.map { it.timeMs })
        assertTrue(draft.lines.all { it.words.isEmpty() })
    }

    @Test fun timedLyricsKeepTheirStamps() {
        val draft = aiLyricDraft("[ti:歌名]\n[00:12.30]第一句\n[00:20.00]第二句", 0)
        assertFalse(draft.estimated)
        assertEquals(listOf(12_300L, 20_000L), draft.lines.map { it.timeMs })
    }

    @Test fun mixedTimingAndWordStampsAreRejected() {
        listOf("[00:01.00]第一句\n第二句", "[00:01.00]<00:01.00>第<00:01.50>一句").forEach { raw ->
            try { aiLyricDraft(raw, 60_000); fail(raw) } catch (_: AiToolInputFailure) { }
        }
    }

    @Test fun lrcRoundTripKeepsWordsRubyAndTranslation() {
        val words = listOf(LyricWord(1000, 1400, "夜"), LyricWord(1400, 1800, "に"))
        val source = listOf(TimedLyric(1000, "夜に", translation = "在夜里", words = words, transliteration = "yo ru\tni"),
            TimedLyric(3000, "心", translation = "心"))
        val parsed = parseLibraryLyrics(aiLyricsToLrc(source, includeWords = true))
        assertEquals(listOf("夜に", "心"), parsed.map { it.text })
        assertEquals(listOf("夜", "に"), parsed[0].words.map { it.text })
        assertEquals(1800L, parsed[0].words.last().endMs)
        assertEquals("在夜里", parsed[0].translation)
        assertEquals("{夜|yo ru}{に|ni}", parsed[0].transliteration)
        // 与原文相同的“译文”在解析时丢弃。
        assertNull(parsed[1].translation)
    }

    @Test fun coverageRequiresLyricsToAppearInSource() {
        val lines = listOf(TimedLyric(0, "Hello, darkness"), TimedLyric(1, "my old friend"))
        assertEquals(1f, aiLyricCoverage(lines, "lyrics: hello darkness! My old friend..."), .001f)
        assertEquals(.5f, aiLyricCoverage(lines, "hello darkness"), .001f)
        val page = aiLyricPageText(JSONObject().put("title", "标题").put("items", JSONArray().put(JSONObject().put("text", "正文"))))
        assertTrue(page.contains("标题") && page.contains("正文"))
    }

    @Test fun annotationsOnlyApplyToTheLyricsTheyWereWrittenFor() {
        val lines = listOf(TimedLyric(0, "今日は晴れ"), TimedLyric(1000, "夜に", translation = "原译文"))
        val fingerprint = AiLyricLayer.fingerprint(lines)
        val layer = JSONObject()
            .put("translation", JSONObject().put("fingerprint", fingerprint).put("lines", JSONObject().put("0", "今天晴天").put("1", "AI 译文")))
            .put("ruby", JSONObject().put("fingerprint", fingerprint).put("lines", JSONObject().put("0", "{今日|きょう}は{晴|は}れ").put("1", "{错|cuo}")))
        val shown = AiLyricLayer.annotate(lines, layer)
        assertEquals("今天晴天", shown[0].translation)
        // 已有译文默认不覆盖；不匹配原文的注音不显示。
        assertEquals("原译文", shown[1].translation)
        assertEquals("{今日|きょう}は{晴|は}れ", shown[0].transliteration)
        assertNull(shown[1].transliteration)
        layer.getJSONObject("translation").put("replace", true)
        assertEquals("AI 译文", AiLyricLayer.annotate(lines, layer)[1].translation)
        // 底层歌词变了，叠加层整体失效。
        val changed = listOf(TimedLyric(0, "今日は雨"), lines[1])
        assertEquals(changed, AiLyricLayer.annotate(changed, layer))
    }

    @Test fun confirmationOnlyNamesTheSongsAndCarriesWarnings() {
        val plan = JSONObject().put("kind", "lyrics").put("lyricChanges", JSONArray().put(JSONObject().put("key", "qq|1")
            .put("title", "夜に駆ける").put("artists", "YOASOBI").put("label", "写入歌词")
            .put("beforeText", "无歌词").put("afterText", "AI 歌词 · AI 生成").put("warning", "歌词由 AI 凭记忆生成，内容和时间都可能不准确，不保证好用。")
            .put("files", JSONObject().put("x.lrc", "[00:00.00]全文不应出现在确认窗"))))
        val groups = aiChangeGroups(plan)
        assertEquals("夜に駆ける · YOASOBI", groups.single().title)
        assertEquals("写入歌词", groups.single().fields.single().label)
        assertFalse(groups.toString().contains("全文不应出现在确认窗"))
        val summary = aiChangeSummary(plan, false)
        assertTrue(summary.startsWith("将修改 1 首歌的歌词"))
        assertTrue(summary.contains("不保证好用"))
    }

    @Test fun lyricToolsAreExposedOnlyAsReadAndPreparation() {
        val tools = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function").getString("name") }
        assertTrue(tools.containsAll(listOf("read_lyrics", "prepare_lyric_annotations", "prepare_write_lyrics", "prepare_remove_lyrics")))
        assertTrue(aiResponseOnlyToolAllowed("read_lyrics"))
        listOf("prepare_lyric_annotations", "prepare_write_lyrics", "prepare_remove_lyrics").forEach { assertFalse(it, aiResponseOnlyToolAllowed(it)) }
        assertFalse(tools.any { it in setOf("write_lyrics", "delete_lyrics", "confirm") })
    }
}
