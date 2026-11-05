package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiSearchScopeTest {
    private val sources = linkedMapOf(
        "lx" to listOf("com.example.lx", "洛雪音源"),
        "qq" to listOf("com.musicone.source.qq", "QQ 音乐"),
        "netease" to listOf("com.musicone.source.netease", "网易云音乐"))
    private fun next(text: String, previous: AiSearchScope = AiSearchScope()) = aiNextSearchScope(text, previous, sources, "qq")
    private fun failure(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        return error!!.message.orEmpty()
    }

    @Test fun customShortNameAndPunctuationMatchLikeNetease() {
        for (text in listOf("用洛雪搜千杯", "用「洛 雪 音源」搜千杯", "搜索洛雪的千杯", "播放洛雪的千杯"))
            assertEquals(text, setOf("lx"), next(text).sourceIds)
        assertEquals(setOf("netease"), next("网易云搜索能用吗，你搜索播放网易云的damidami").sourceIds)
    }

    @Test fun genericSuffixesWorkForOtherPackagesWithoutPlatformHardcoding() {
        assertTrue(aiExplicitSourceRequested("用星河搜歌", listOf("example.stars", "星河音乐音源")))
        assertFalse(aiExplicitSourceRequested("我喜欢星河的歌", listOf("星河音源")))
    }

    @Test fun collidingShortNamesFailButCompleteNamesDisambiguate() {
        val ambiguous = mapOf("a" to listOf("洛雪音源"), "b" to listOf("洛雪音乐"))
        assertTrue(failure { aiExplicitSearchSourceIds("用洛雪搜千杯", ambiguous, ambiguous.keys) }.contains("歧义"))
        assertEquals(setOf("a"), aiExplicitSearchSourceIds("用洛雪音源搜千杯", ambiguous, ambiguous.keys))
        assertEquals(setOf("b"), aiExplicitSearchSourceIds("用洛雪音乐搜千杯", ambiguous, ambiguous.keys))
        assertEquals(setOf("a"), aiExplicitSearchSourceIds("不用洛雪音乐，用洛雪音源搜千杯", ambiguous, ambiguous.keys))
    }

    @Test fun followUpsKeepSourceUntilASeparateTaskOrExplicitSwitch() {
        val initial = next("用洛雪搜千杯")
        for (text in listOf("洛雪回复什么？它明明能搜索", "再搜一次", "继续", "好的", "它返回了什么"))
            assertEquals(text, setOf("lx"), next(text, initial).sourceIds)
        assertEquals(setOf("netease"), next("改用网易云搜damidami", initial).sourceIds)
        assertEquals(setOf("netease"), next("改用网易云", initial).sourceIds)
        assertTrue(next("推荐几首跑步的歌", initial).sourceIds.isEmpty())
        assertTrue(next("你好", initial).sourceIds.isEmpty())
        assertTrue(next("所有音源都搜", initial).sourceIds.isEmpty())
    }

    @Test fun exclusionWinsAndAnExplicitNewChoiceCanRestoreSource() {
        val scope = next("不要用QQ搜，用洛雪搜千杯")
        assertEquals(setOf("lx"), scope.sourceIds)
        assertEquals(setOf("qq"), scope.excludedIds)
        assertNull(aiSearchPriority(scope, "qq", "qq") { 1 })
        val switched = next("改用QQ搜千杯", scope)
        assertEquals(setOf("qq"), switched.sourceIds)
        assertFalse("qq" in switched.excludedIds)
        assertTrue(next("不要用洛雪搜", scope).sourceIds.isEmpty())
    }

    @Test fun modelSourceCannotSilentlyBecomeAutomaticOrEscapeScope() {
        val scope = next("用洛雪搜千杯")
        assertEquals("lx", aiRequestedSearchSource(scope, "lx", null))
        assertNull(aiRequestedSearchSource(scope, null, null))
        assertTrue(failure { aiRequestedSearchSource(scope, "qq", null) }.contains("其他音源"))
        failure { aiRequestedSearchSource(scope, "qq", "old-qq-cursor") }
        assertTrue(failure { aiRequestedSearchSource(AiSearchScope(), "lx", null) }.contains("不能静默换源"))
    }

    @Test fun qqCannotEnterCandidatesWhenUserSpecifiesLuoxue() {
        val scope = next("用洛雪搜千杯")
        var automaticCalls = 0
        val candidates = sources.keys.mapNotNull { id ->
            aiSearchPriority(scope, id, "qq") { automaticCalls++; if (id == "qq") 1 else 2 }?.let { id to it }
        }
        assertEquals(listOf("lx" to 0), candidates)
        assertEquals(0, automaticCalls)
    }

    @Test fun fallbackRequiresUserPermissionAndKeepsSpecifiedSourceFirst() {
        val strict = next("用洛雪搜千杯")
        val allowed = next("找不到再搜其他音源", strict)
        assertEquals(setOf("lx"), allowed.sourceIds)
        assertTrue(allowed.allowFallback)
        assertEquals(0, aiSearchPriority(allowed, "lx", "qq") { 2 })
        assertEquals(1, aiSearchPriority(allowed, "qq", "qq") { 1 })
        assertFalse(next("用网易云搜damidami", allowed).allowFallback)
        assertFalse(next("没结果也不要搜其他音源", strict).allowFallback)
        val denied = next("不要兜底，继续搜", allowed)
        assertFalse(denied.allowFallback)
        assertEquals(setOf("lx"), denied.sourceIds)
        assertEquals(setOf("lx"), next("不允许跨音源，继续搜", allowed).sourceIds)
        assertEquals(setOf("lx"), next("不要跨音源，继续搜", allowed).sourceIds)
    }

    @Test fun unknownAndDisabledSourcesReportInsteadOfFallingBack() {
        assertTrue(failure { next("用陌生音源搜千杯") }.contains("未识别"))
        assertTrue(failure { next("用陌生搜千杯") }.contains("未识别"))
        assertTrue(failure { next("不要用QQ，用陌生搜千杯") }.contains("未识别"))
        assertTrue(failure { aiExplicitSearchSourceIds("用洛雪搜千杯", sources, setOf("qq")) }.contains("未启用"))
        assertTrue(next("在我的歌单里搜千杯").sourceIds.isEmpty())
        assertTrue(next("我想在下雨时听歌，找几首").sourceIds.isEmpty())
    }

    @Test fun persistedScopeRecoversFollowUpsAndProcessesInterveningNonSearchTurns() {
        val requests = JSONArray().put("用洛雪搜千杯")
        val checkpoint = JSONObject().put("activeTurnId", "one").put("latestUserRequest", "用洛雪搜千杯").put("userRequests", requests)
        assertEquals(setOf("lx"), aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq").sourceIds)
        requests.put("洛雪回复什么？它明明能搜索")
        checkpoint.put("activeTurnId", "two").put("latestUserRequest", requests.getString(1))
        assertEquals(setOf("lx"), aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq").sourceIds)
        val restored = JSONObject(checkpoint.toString())
        assertEquals(setOf("lx"), aiUpdateSearchScope(restored, sources, sources.keys, "qq").sourceIds)
        requests.put("你好").put("再推荐几首")
        checkpoint.put("activeTurnId", "four").put("latestUserRequest", "再推荐几首")
        assertTrue(aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq").sourceIds.isEmpty())
    }

    @Test fun unresolvedSourceCannotBecomeAutomaticOnRetryButNewTaskCanResetIt() {
        val requests = JSONArray().put("用陌生音源搜千杯").put("再搜一次")
        val checkpoint = JSONObject().put("userRequests", requests).put("activeTurnId", "two").put("latestUserRequest", "再搜一次")
        assertTrue(failure { aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq") }.contains("未识别"))
        requests.put("用网易云搜damidami")
        checkpoint.put("activeTurnId", "three").put("latestUserRequest", "用网易云搜damidami")
        assertEquals(setOf("netease"), aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq").sourceIds)
        requests.put("推荐跑步歌单")
        checkpoint.put("activeTurnId", "four").put("latestUserRequest", "推荐跑步歌单")
        assertTrue(aiUpdateSearchScope(checkpoint, sources, sources.keys, "qq").sourceIds.isEmpty())
    }

    @Test fun restoredConstraintRejectsDisabledSourceAndCurrentSourceConstraintIsBinding() {
        val checkpoint = JSONObject().put("latestUserRequest", "再搜一次").put("activeTurnId", "two")
            .put("userRequests", JSONArray().put("用洛雪搜千杯").put("再搜一次"))
        failure { aiUpdateSearchScope(checkpoint, sources, setOf("qq"), "qq") }
        val scope = next("只用当前音源搜千杯")
        assertEquals(setOf("qq"), scope.sourceIds)
        assertNull(aiSearchPriority(scope, "lx", "qq") { 2 })
    }
}
