package com.musicone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * AI 歌词整理工具。所有修改只生成待确认计划：
 * - 翻译、注音按现有行号叠加，不能改原文、时间和逐字；
 * - 写歌词必须注明来源：音源 > 用户提供 > 网页 > AI 凭记忆（仅用户坚持时），程序逐项核对依据；
 * - 原歌词文件只能取消关联，不能删除；AI 写的歌词放在独立目录。
 */
internal class AiLyricTools(context: Context, private val library: AiLibraryTools, private val checkpoint: JSONObject) {
    private val store = MusicLibraryStore.get(context)
    private val resolver by lazy { PlatformPlaybackResolver(context.applicationContext) }
    // 本轮抓取过的网页正文，只用于核对 web 来源的歌词，换轮即清空。
    private val pages = LinkedHashMap<String, String>()
    private var pagesTurn = ""

    private class Current(val track: MusicTrack, val raw: String?, val layer: JSONObject, val base: List<TimedLyric>,
        val aiFile: JSONObject?, val manualPath: String?)

    fun rememberPage(url: String, result: JSONObject) {
        val turn = checkpoint.optString("activeTurnId")
        if (turn != pagesTurn) { pages.clear(); pagesTurn = turn }
        pages[url.trim()] = aiLyricPageText(result).take(200_000)
        while (pages.size > 6) pages.remove(pages.keys.first())
    }

    suspend fun read(trackRef: String, cursor: String?): JSONObject {
        val current = current(trackRef)
        val base = current.base
        val start = cursor?.toIntOrNull() ?: 0
        if (start !in 0..base.size) throw AiToolInputFailure("invalid_cursor", "歌词游标无效，请省略cursor从头读取")
        val end = minOf(base.size, start + 120)
        val fingerprint = AiLyricLayer.fingerprint(base)
        fun annotation(kind: String) = current.layer.optJSONObject(kind)?.takeIf { it.optString("fingerprint") == fingerprint }?.optJSONObject("lines")
        val translation = annotation("translation")
        val ruby = annotation("ruby")
        val lines = JSONArray((start until end).map { index ->
            val line = base[index]
            JSONObject().put("index", index).put("timeMs", line.timeMs).put("text", line.text).apply {
                line.translation?.let { put("translation", it) }
                lyricRubyAnchoredText(line)?.let { put("ruby", it) }
                translation?.optional(index.toString())?.let { put("aiTranslation", it) }
                ruby?.optional(index.toString())?.let { put("aiRuby", it) }
            }
        })
        return JSONObject().put("trackRef", trackRef).put("title", current.track.title).put("artists", current.track.artists)
            .put("durationMs", current.track.durationMs)
            .put("lyrics", when { current.aiFile != null -> "ai_written"; base.isNotEmpty() -> "original"; else -> "none" })
            .put("aiOrigin", current.aiFile?.optString("origin") ?: JSONObject.NULL)
            .put("timingEstimated", current.aiFile?.optBoolean("estimated") ?: false)
            .put("manualLyricsLinked", current.manualPath != null)
            .put("wordTiming", base.any { it.words.isNotEmpty() })
            .put("count", base.size).put("lines", lines)
            .put("complete", end == base.size).put("cursor", if (end < base.size) end.toString() else JSONObject.NULL)
            .apply { if (base.isEmpty()) put("note", "这首歌没有歌词；用户需要时按来源优先级用prepare_write_lyrics写入") }
    }

    suspend fun prepareAnnotations(trackRef: String, kind: String, items: JSONArray, replace: Boolean): JSONObject {
        if (kind !in AiLyricLayer.annotationKinds) throw AiToolInputFailure("invalid_kind", "kind只能是translation或ruby")
        val current = current(trackRef)
        val base = current.base
        if (base.isEmpty()) throw AiToolInputFailure("no_lyrics", "这首歌没有歌词，不能添加翻译或注音；可先用prepare_write_lyrics写入歌词")
        val name = if (kind == "translation") "翻译" else "注音"
        val fingerprint = AiLyricLayer.fingerprint(base)
        val existing = current.layer.optJSONObject(kind)?.takeIf { it.optString("fingerprint") == fingerprint }
        val merged = JSONObject(existing?.optJSONObject("lines")?.toString() ?: "{}")
        val invalid = mutableListOf<Int>()
        var skipped = 0
        var added = 0
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val row = item.optInt("index", -1)
            val text = item.optString("text").trim()
            val line = base.getOrNull(row)
            val valid = line != null && text.isNotEmpty() && text.length <= 400 &&
                if (kind == "translation") text != line.text else lyricRubyAnchors(line.text, text) != null
            if (!valid) { invalid += row; continue }
            val occupied = if (kind == "translation") line!!.translation else line!!.transliteration
            if (!replace && !occupied.isNullOrBlank()) { skipped++; continue }
            merged.put(row.toString(), text)
            added++
        }
        if (invalid.isNotEmpty()) throw AiToolInputFailure("invalid_lines", "第 ${invalid.take(10).joinToString()} 行无效。行号从0开始，以read_lyrics为准" +
            if (kind == "ruby") "；注音写成{原文片段|读音}，去掉标注后必须与该行原文逐字一致" else "；译文不能为空或与原文相同")
        if (added == 0) throw AiToolInputFailure("nothing_to_write", "这些行已有$name，没有可写入的行；只有用户明确要求替换时才传replace=true")
        val after = JSONObject(current.layer.toString()).put(kind, JSONObject().put("fingerprint", fingerprint)
            .put("replace", replace || existing?.optBoolean("replace") == true).put("lines", merged))
        val change = change(current, after, "添加$name",
            existing?.let { "AI $name ${it.optJSONObject("lines")?.length() ?: 0} 行" } ?: "无 AI $name", "AI $name ${merged.length()} 行")
        return library.prepareLyrics(listOf(change)).put("addedLines", added).put("skippedLines", skipped)
            .apply { if (kind == "ruby") put("displayHint", "注音需在设置“歌词显示内容”选择“原文、注音和翻译”后显示") }
    }

    suspend fun prepareWrite(trackRef: String, origin: String, lyrics: String?, evidence: String?, sourceTrackRef: String?,
        replace: Boolean): JSONObject {
        val current = current(trackRef)
        if (current.base.isNotEmpty() && !replace) throw AiToolInputFailure("lyrics_exist",
            "这首歌已有歌词，只有用户明确要求替换时才传replace=true；替换不会改动原歌词文件")
        val track = current.track
        fun draftText() = lyrics?.takeIf { it.isNotBlank() } ?: throw AiToolInputFailure("missing_lyrics", "请在lyrics中提交歌词全文")
        val draft: AiLyricDraft
        val label: String
        val warning: String?
        when (origin) {
            "source" -> {
                val ref = sourceTrackRef ?: throw AiToolInputFailure("missing_source", "source来源须提供sourceTrackRef（搜索结果中的同一首歌）")
                val other = library.resolveTracks(listOf(ref)).single()
                if (AiLyricLayer.key(other) == AiLyricLayer.key(track)) throw AiToolInputFailure("same_track", "sourceTrackRef须是其他音源或其他版本的同一首歌")
                if (track.durationMs > 0 && other.durationMs > 0 && abs(track.durationMs - other.durationMs) > 8_000)
                    throw AiToolInputFailure("duration_mismatch", "两首歌时长相差${abs(track.durationMs - other.durationMs) / 1000}秒，可能不是同一版本")
                val lines = runCatching { resolver.sourceLyrics(other) }.getOrElse {
                    throw AiToolInputFailure("source_lyrics_failed", "读取该音源歌词失败：${it.message ?: "未知错误"}")
                }
                if (lines.isEmpty()) throw AiToolInputFailure("source_no_lyrics", "这首歌在该音源也没有歌词")
                val content = aiLyricsToLrc(lines, includeWords = true)
                draft = AiLyricDraft(content, parseLibraryLyrics(content), estimated = false)
                label = "来自${other.source.label}"
                warning = "歌词取自${other.source.label}的同一首歌，版本不同时可能对不齐。"
            }
            "user_provided" -> {
                draft = aiLyricDraft(draftText(), track.durationMs)
                val corpus = checkpoint.optJSONArray("userRequests")?.let { requests -> (0 until requests.length()).joinToString("\n") { requests.optString(it) } }.orEmpty()
                if (aiLyricCoverage(draft.lines, corpus) < .6f) throw AiToolInputFailure("not_from_user",
                    "提交的歌词与用户消息里的内容对不上；user_provided只能使用用户在对话中贴出的歌词原文")
                label = "用户提供"
                warning = if (draft.estimated) "时间按歌曲时长平均估算，可能对不齐，可在播放页调整歌词偏移。" else null
            }
            "web" -> {
                val url = evidence?.trim()?.takeIf { it.isNotEmpty() } ?: throw AiToolInputFailure("missing_evidence", "web来源须在evidence填写本轮web_fetch抓取的网址")
                val page = pages[url] ?: throw AiToolInputFailure("page_not_fetched", "请先在本轮用web_fetch抓取该网页，evidence须与抓取网址一致")
                draft = aiLyricDraft(draftText(), track.durationMs)
                if (aiLyricCoverage(draft.lines, page) < .6f) throw AiToolInputFailure("not_from_page", "提交的歌词与抓取网页的内容对不上，只能使用网页原文")
                label = "来自网页"
                warning = "歌词来自网页，内容可能有误" + (if (draft.estimated) "，时间为估算、可能对不齐" else "") + "。"
            }
            "model" -> {
                val quote = aiLyricNormalize(evidence.orEmpty())
                if (quote.length < 2 || !aiLyricNormalize(checkpoint.optString("latestUserRequest")).contains(quote))
                    throw AiToolInputFailure("not_requested", "只有用户明确坚持要AI写歌词时才能用model来源，evidence须原样引用用户本轮的这句话")
                draft = aiLyricDraft(draftText(), track.durationMs)
                label = "AI 生成"
                warning = "歌词由 AI 凭记忆生成，内容和时间都可能不准确，不保证好用。"
            }
            else -> throw AiToolInputFailure("invalid_origin", "origin只能是source、user_provided、web或model")
        }
        val name = "${UUID.randomUUID()}.lrc"
        val files = JSONObject().put(name, draft.content)
        current.aiFile?.let { old -> readAiFile(old)?.let { files.put(old.getString("name"), it) } }
        val after = JSONObject(current.layer.toString()).put("file", JSONObject().put("name", name).put("origin", origin)
            .put("label", label).put("estimated", draft.estimated))
        val before = when {
            current.aiFile != null -> "AI 歌词（${current.aiFile.optString("label")}）"
            current.base.isNotEmpty() -> "原歌词（文件保留，不改动）"
            else -> "无歌词"
        }
        val change = change(current, after, "写入歌词", before, "AI 歌词 · $label" + if (draft.estimated) " · 时间为估算" else "")
            .put("files", files).apply { warning?.let { put("warning", it) } }
        return library.prepareLyrics(listOf(change)).put("lineCount", draft.lines.size).put("timingEstimated", draft.estimated)
            .apply { warning?.let { put("warning", "回复时须提醒用户：$it") } }
    }

    suspend fun prepareRemove(trackRef: String, target: String): JSONObject {
        val current = current(trackRef)
        val change = when (target) {
            "ai_lyrics" -> {
                val file = current.aiFile ?: throw AiToolInputFailure("no_ai_lyrics", "这首歌没有AI写入的歌词")
                // AI 歌词上的翻译、注音随之失效，一并移除；原歌词上的仍保留。
                val fingerprint = AiLyricLayer.fingerprint(current.base)
                val after = JSONObject(current.layer.toString()).apply {
                    remove("file")
                    AiLyricLayer.annotationKinds.forEach { kind -> if (optJSONObject(kind)?.optString("fingerprint") == fingerprint) remove(kind) }
                }
                change(current, after, "删除 AI 歌词", "AI 歌词（${file.optString("label")}）", "恢复显示原歌词（如有）")
                    .put("files", JSONObject().apply { readAiFile(file)?.let { put(file.getString("name"), it) } })
            }
            "translation", "ruby" -> {
                val name = if (target == "translation") "翻译" else "注音"
                val existing = current.layer.optJSONObject(target) ?: throw AiToolInputFailure("no_annotation", "这首歌没有AI添加的$name")
                change(current, JSONObject(current.layer.toString()).apply { remove(target) }, "删除 AI $name",
                    "AI $name ${existing.optJSONObject("lines")?.length() ?: 0} 行", "无")
            }
            "manual_link" -> {
                val path = current.manualPath ?: throw AiToolInputFailure("no_manual_link", "这首歌没有手动关联的歌词文件")
                change(current, current.layer, "取消歌词关联", File(path).name, "取消关联（文件保留在原处）")
                    .put("lyricPath", JSONObject().put("before", path).put("after", JSONObject.NULL))
            }
            else -> throw AiToolInputFailure("invalid_target", "target只能是ai_lyrics、translation、ruby或manual_link")
        }
        return library.prepareLyrics(listOf(change))
    }

    private fun change(current: Current, after: JSONObject, label: String, beforeText: String, afterText: String): JSONObject =
        JSONObject().put("key", AiLyricLayer.key(current.track)).put("songId", current.track.id)
            .put("title", current.track.title).put("artists", current.track.artists).put("label", label)
            .put("beforeText", beforeText).put("afterText", afterText)
            .put("before", current.raw ?: JSONObject.NULL)
            .put("after", after.takeIf { it.length() > 0 }?.toString() ?: JSONObject.NULL)

    private suspend fun current(trackRef: String): Current {
        val track = library.resolveTracks(listOf(trackRef)).single()
        val (raw, manual) = withContext(Dispatchers.IO) {
            AiLyricLayer.raw(store.database(), AiLyricLayer.key(track)) to
                track.source.supportsManualLyrics.takeIf { it }?.let { store.lyricAsset(track.id).first }
        }
        val layer = raw?.let(::JSONObject) ?: JSONObject()
        val aiFile = layer.optJSONObject("file")?.takeIf { File(AiLyricLayer.directory(store), it.optString("name")).isFile }
        val base = runCatching { AiLyricLayer.base(store, track, layer) { resolver.sourceLyrics(track) } }.getOrElse {
            throw AiToolInputFailure("lyrics_unavailable", "读取歌词失败：${it.message ?: "未知错误"}")
        }
        return Current(track, raw, layer, base, aiFile, manual)
    }

    private suspend fun readAiFile(file: JSONObject): String? = withContext(Dispatchers.IO) {
        File(AiLyricLayer.directory(store), file.optString("name")).takeIf { it.isFile }?.readText()
    }
}
