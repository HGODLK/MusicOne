package com.musicone

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 选源来自用户原话；模型参数不能建立、清除或扩大任务约束。 */
internal data class AiSearchScope(
    val sourceIds: Set<String> = emptySet(),
    val excludedIds: Set<String> = emptySet(),
    val currentOnly: Boolean = false,
    val allowFallback: Boolean = false,
    val resolutionError: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("sourceIds", JSONArray(sourceIds.toList()))
        .put("excludedIds", JSONArray(excludedIds.toList())).put("currentOnly", currentOnly).put("allowFallback", allowFallback)
        .put("resolutionError", resolutionError)

    companion object {
        fun fromJson(value: JSONObject?) = AiSearchScope(
            value?.optJSONArray("sourceIds")?.strings().orEmpty().toSet(),
            value?.optJSONArray("excludedIds")?.strings().orEmpty().toSet(),
            value?.optBoolean("currentOnly") == true, value?.optBoolean("allowFallback") == true, value?.optional("resolutionError"))
    }
}

internal fun aiNormalizeSourceText(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

/** 完整名称优先；通用后缀只生成候选简称，重名必须由用户消歧。 */
internal fun aiSourceNameTokens(names: List<String>): Set<String> {
    val tokens = names.map(::aiNormalizeSourceText).filter { it.length >= 2 }.toMutableSet()
    names.forEach { name ->
        var short = aiNormalizeSourceText(name)
        while (true) {
            val suffix = listOf("音源", "音乐", "平台").firstOrNull { short.endsWith(it) && short.length - it.length >= 2 } ?: break
            short = short.removeSuffix(suffix)
            tokens += short
        }
    }
    if (tokens.any { "bilibili" in it || "哔哩" in it }) tokens.addAll(listOf("b站", "哔哩哔哩"))
    if (tokens.any { "netease" in it || "网易" in it }) tokens.addAll(listOf("网易", "网易云", "网易云音乐"))
    if (tokens.any { "kugou" in it || "酷狗" in it }) tokens.addAll(listOf("酷狗", "酷狗音乐"))
    if (tokens.any { it == "qq" || it.endsWith("sourceqq") || it.startsWith("qq音乐") }) tokens.addAll(listOf("qq", "qq音乐"))
    return tokens
}

internal fun aiHasSearchAction(text: String): Boolean =
    listOf("搜", "找", "检索", "推荐", "播放", "放首", "放一首", "我想听").any(text::contains)

private val excludedPrefixes = listOf("不用", "不要", "不要用", "别用", "别去", "不要去", "不在", "排除", "除了")
private val selectedPrefixes = listOf("用", "去", "在", "从", "通过", "换到", "切到", "搜索", "播放", "检索")

private data class AiSourceMention(val id: String, val token: String, val start: Int, val end: Int, val excluded: Boolean, val selected: Boolean)

private fun aiSourceMentions(request: String, sources: Map<String, List<String>>): List<AiSourceMention> {
    val text = aiNormalizeSourceText(request)
    val mentions = sources.flatMap { (id, names) -> aiSourceNameTokens(names).flatMap { token ->
        val matches = mutableListOf<AiSourceMention>()
        var start = text.indexOf(token)
        while (start >= 0) {
            val before = text.substring(0, start)
            val after = text.substring(start + token.length)
            val excluded = excludedPrefixes.any(before::endsWith)
            val selected = !excluded && (listOf("改用", "换用", "换到", "切到").any(before::endsWith) ||
                aiHasSearchAction(text) && (selectedPrefixes.any(before::endsWith) || listOf("搜", "找", "里", "上", "的歌").any(after::startsWith)))
            matches += AiSourceMention(id, token, start, start + token.length, excluded, selected)
            start = text.indexOf(token, start + token.length)
        }
        matches
    } }
    return mentions.filter { hit -> mentions.none { other ->
        other.start <= hit.start && other.end >= hit.end && other.token.length > hit.token.length
    } }
}

internal fun aiExplicitSourceRequested(request: String, sourceNames: List<String>): Boolean =
    !aiSourceExcluded(request, sourceNames, true) && aiSourceMentions(request, mapOf("source" to sourceNames)).any { it.selected }

internal fun aiExplicitSearchSourceIds(request: String, sources: Map<String, List<String>>, enabledIds: Set<String>): Set<String> {
    val mentions = aiSourceMentions(request, sources).filter { it.selected }
    mentions.groupBy { it.start to it.end }.values.forEach { hits ->
        require(hits.map { it.id }.distinct().size == 1) {
            "音源名称存在歧义，请使用完整名称或 ID：" + hits.map { sources.getValue(it.id).last() }.distinct().joinToString()
        }
    }
    val excluded = aiSourceMentions(request, sources).filter { it.excluded }.map { it.id }.toSet()
    val selected = mentions.map { it.id }.toSet() - excluded
    val unavailable = selected - enabledIds
    require(unavailable.isEmpty()) { "明确指定的音源未启用或不可用：" + unavailable.joinToString { sources.getValue(it).last() } }
    return selected
}

internal fun aiSourceExcluded(request: String, names: List<String>, current: Boolean): Boolean {
    val text = aiNormalizeSourceText(request)
    if (!current && aiCurrentSourceOnly(text)) return true
    return aiSourceNameTokens(names).any { token -> excludedPrefixes.any { text.contains(it + token) } }
}

private fun aiCurrentSourceOnly(text: String): Boolean =
    listOf("不要跨", "不跨", "不能跨", "别跨", "只用当前", "仅当前", "只在当前", "不要用其他").any(text::contains)

/** 同一任务的追问继承约束；独立新请求或明确放开来源时结束继承。 */
internal fun aiNextSearchScope(request: String, previous: AiSearchScope, sources: Map<String, List<String>>, mainId: String): AiSearchScope {
    val text = aiNormalizeSourceText(request)
    val mentions = aiSourceMentions(request, sources)
    val selected = aiExplicitSearchSourceIds(request, sources, sources.keys)
    val excluded = mentions.filter { it.excluded }.map { it.id }.toSet()
    val fallbackDenied = listOf("不要跨", "不跨", "不能跨", "别跨", "不允许跨", "不要用其他", "不要搜其他", "别搜其他", "不要兜底", "禁止兜底").any(text::contains)
    val genericPermission = !fallbackDenied && listOf("可以跨音源", "允许跨音源", "所有音源都搜", "其他音源也可以", "其他来源也可以", "不限音源", "不限制音源").any(text::contains)
    val fallback = !fallbackDenied && (Regex("(找不到|没结果|无结果|没有结果).*(再搜|再找|可以搜|允许搜|换用|改用).*(其他|别的|其它)").containsMatchIn(text) ||
        listOf("允许其他来源兜底", "允许其他音源兜底").any(text::contains))
    val followUp = mentions.any { it.id in previous.sourceIds } ||
        listOf("再", "继续", "重试", "重新", "换个", "换一", "刚才", "上次", "上一", "那首", "它", "这个", "那个", "还是", "为什么").any(text::startsWith) ||
        text in setOf("好", "好的", "可以", "行", "是的", "对", "对的", "没错") || excluded.isNotEmpty() || fallback || fallbackDenied
    val inherited = if (followUp && !genericPermission) previous else AiSearchScope()
    val namesCurrent = listOf("只用当前", "仅当前", "只在当前").any(text::contains)
    val ids = when {
        selected.isNotEmpty() -> selected
        namesCurrent || aiCurrentSourceOnly(text) && inherited.sourceIds.isEmpty() -> setOf(mainId)
        genericPermission -> emptySet()
        else -> inherited.sourceIds - excluded
    }
    val exclusions = (if (followUp) previous.excludedIds else emptySet()) + excluded - selected
    require(selected.isNotEmpty() || genericPermission || inherited.resolutionError == null) { inherited.resolutionError.orEmpty() }
    // 指定了未知音源时明确失败，不能把未匹配当成自动搜索许可。
    if (selected.isEmpty() && aiHasSearchAction(text)) {
        val target = request.split(Regex("[，。！？,!?；;]")).firstNotNullOfOrNull { clause ->
            Regex("^(?:请|麻烦|帮我)?(?:只用|仅用|通过|换到|切到|用|在|从)(.{2,40}?)(?:重新搜|搜索|搜|找|检索)")
                .find(aiNormalizeSourceText(clause))?.groupValues?.get(1)
        }
        val generic = setOf("当前音源", "当前", "其他音源", "其它音源", "其他", "所有音源", "全部音源", "本地歌单", "歌单", "曲库", "关键词", "关键字", "歌名", "歌曲名", "艺人名", "歌手名")
        val localScope = target != null && Regex("(?:本地|我的|这个|那个)?(?:歌单|曲库|资料库)(?:里|中)?").matches(target)
        require(target == null || target in generic || localScope) { "未识别指定音源：$target，请使用已安装音源的完整名称或 ID" }
    }
    return AiSearchScope(ids, exclusions, if (selected.isNotEmpty() || genericPermission) false else namesCurrent || inherited.currentOnly,
        !fallbackDenied && if (selected.isNotEmpty()) fallback else fallback || inherited.allowFallback)
}

internal fun aiUpdateSearchScope(checkpoint: JSONObject, sources: Map<String, List<String>>, enabledIds: Set<String>, mainId: String): AiSearchScope {
    val request = checkpoint.optString("latestUserRequest")
    val turn = checkpoint.optString("activeTurnId", request)
    val stored = checkpoint.optJSONObject("searchScope")
    var scope = AiSearchScope.fromJson(stored)
    val requests = checkpoint.optJSONArray("userRequests")?.strings().orEmpty().let { if (it.lastOrNull() == request) it else it + request }
    if (stored?.optString("turnId") != turn || stored.optString("request") != request) {
        // 从本地用户原话恢复中间轮次，避免没有搜索的聊天让旧约束粘住新任务。
        val start = stored?.optInt("requestCount", 0)?.coerceIn(0, requests.size) ?: 0
        requests.drop(start).forEachIndexed { index, text ->
            scope = if (start + index == requests.lastIndex) aiNextSearchScope(text, scope, sources, mainId)
                else try { aiNextSearchScope(text, scope, sources, mainId) }
                    catch (error: IllegalArgumentException) { AiSearchScope(resolutionError = error.message) }
        }
    }
    val unavailable = scope.sourceIds - enabledIds
    require(unavailable.isEmpty()) { "指定音源未启用或不可用：" + unavailable.joinToString { sources[it]?.lastOrNull() ?: it } }
    checkpoint.put("searchScope", scope.toJson().put("turnId", turn).put("request", request).put("requestCount", requests.size))
    return scope
}

internal fun aiRequestedSearchSource(scope: AiSearchScope, id: String?, cursor: String?): String? {
    if (id == null) return null
    require(id !in scope.excludedIds) { "用户已排除此音源" }
    require(scope.sourceIds.isEmpty() || id in scope.sourceIds || scope.allowFallback && cursor != null) { "用户已明确指定其他音源，请按指定音源搜索" }
    require(cursor != null || id in scope.sourceIds) { "本轮未授权直接选择此音源，请省略 sourceId 使用程序路由；不能静默换源" }
    return id
}

/** 指定来源独立成最先执行的一组，其他来源只有明确兜底许可才进入候选。 */
internal inline fun aiSearchPriority(scope: AiSearchScope, id: String, mainId: String, automaticTier: () -> Int?): Int? = when {
    id in scope.excludedIds || scope.currentOnly && id != mainId -> null
    id in scope.sourceIds -> 0
    scope.sourceIds.isNotEmpty() && !scope.allowFallback -> null
    else -> automaticTier()
}
