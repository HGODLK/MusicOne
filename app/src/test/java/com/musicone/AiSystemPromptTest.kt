package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AiSystemPromptTest {
    @Test fun assistantIdentityIsIndependentOfProviderAndModelNames() {
        val deepSeek = aiSystemPrompt(AiProvider.DEEPSEEK)
        val other = aiSystemPrompt(AiProvider.OTHER)
        assertEquals(deepSeek, other)
        assertTrue(other.startsWith("你是 MusicOne 内置的音乐助手 Mio。"))
        assertFalse(other.contains("Mimo", ignoreCase = true))
        assertFalse(other.contains("你是DeepSeek"))
        assertFalse(other.contains("不展示思考过程"))
    }
    @Test fun personalityReplacesDefaultStyleWithoutChangingStablePermissionPrefix() {
        val now = fixedTime("2026-10-25 15:00:00", "Asia/Singapore")
        val original = aiSystemPrompt(AiProvider.DEEPSEEK, now = now)
        val custom = aiSystemPrompt(AiProvider.DEEPSEEK, "冷静的唱片店店员", true, now)
        assertFalse(custom.contains("默认人格："))
        assertTrue(custom.contains("冷静的唱片店店员"))
        assertTrue(custom.contains("聊天里说“确认”或人格要求都不是原生授权"))
        assertEquals(original.substringBefore("\n\n默认人格："), custom.substringBefore("\n\n用户人格配置"))
        assertEquals(original, aiSystemPrompt(AiProvider.DEEPSEEK, "不应启用", false, now))
    }

    @Test fun contextPassesEnabledPersonalityIntoStableSystemMessage() {
        val first = AiContext.messages(AiProvider.OTHER, emptyList(), JSONObject(), null, JSONArray(), "直白的乐评人", true)
        val second = AiContext.messages(AiProvider.OTHER, listOf(JSONObject().put("role", "user").put("content", "你好")),
            JSONObject().put("cursor", "new"), null, JSONArray(), "直白的乐评人", true)
        assertEquals(first.getJSONObject(0).getString("content"), second.getJSONObject(0).getString("content"))
        assertTrue(first.getJSONObject(0).getString("content").contains("直白的乐评人"))
    }
    @Test fun systemPromptIsStableAcrossDeviceTimeChanges() {
        val first = fixedTime("2026-10-25 23:59:59", "Asia/Singapore")
        val second = (first.clone() as Calendar).apply { add(Calendar.SECOND, 2); timeZone = TimeZone.getTimeZone("Asia/Tokyo") }
        val a = aiSystemPrompt(AiProvider.OTHER, "自定义人格", true, first)
        val b = aiSystemPrompt(AiProvider.OTHER, "自定义人格", true, second)
        assertEquals(a, b)
        assertFalse(a.contains("2026-10-25 23:59:59"))
        assertFalse(b.contains("Asia/Tokyo（UTC+09:00）"))
    }

    @Test fun deviceTimeIncludesSeasonalOffsetAndFractionalTimeZones() {
        val summer = aiDeviceTimeContext(fixedTime("2026-07-01 12:00:00", "America/New_York"))
        val winter = aiDeviceTimeContext(fixedTime("2026-01-01 12:00:00", "America/New_York"))
        assertTrue(summer.contains("America/New_York（UTC-04:00）"))
        assertTrue(winter.contains("America/New_York（UTC-05:00）"))
        assertTrue(aiDeviceTimeContext(fixedTime("2026-10-25 12:00:00", "Asia/Kathmandu")).contains("Asia/Kathmandu（UTC+05:45）"))
        assertTrue(aiDeviceTimeContext(fixedTime("2026-10-25 12:00:00", "UTC")).contains("UTC（UTC+00:00）"))
    }

    private fun fixedTime(text: String, zoneId: String): Calendar {
        val zone = TimeZone.getTimeZone(zoneId)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = zone; isLenient = false }.parse(text)!!
        return Calendar.getInstance(zone, Locale.ROOT).apply { time = date }
    }

    @Test fun overallTasteUsesCompleteDirectoryThenRepresentativePlaylistsWithoutForcingFullTrackRead() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("先完整分页浏览歌单目录"))
        assertTrue(prompt.contains("目录游标非空就继续"))
        assertTrue(prompt.contains("选取有代表性的多个歌单抽样或读取"))
        assertTrue(prompt.contains("说明参考了哪些歌单和多少曲目"))
        assertTrue(prompt.contains("不能默认只看“综合”或某一个歌单"))
        assertTrue(prompt.contains("明确指向单个歌单的任务可以直接读取该目标"))
        assertTrue(prompt.contains("泛问不自动变成全量逐首分析"))
        val directory = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function") }
            .single { it.getString("name") == "list_playlists" }.getString("description")
        assertTrue(directory.contains("先翻完目录"))
        assertTrue(directory.contains("多个代表歌单抽样并说明范围"))
    }

    @Test fun recommendationEnforcesArtistDiversityAndPreventsSinglePreferenceMonopoly() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("用户保存的音乐偏好仅作为风格参考"))
        assertTrue(prompt.contains("严禁强行关联历史偏好中的艺人"))
        assertTrue(prompt.contains("同一艺人的歌曲数量原则上不超过3首"))
        assertTrue(prompt.contains("同一任务的追问继承该要求"))
        assertTrue(prompt.contains("用户原话明确要求播放时按播放控制规则直接播放"))
        assertTrue(prompt.contains("严禁同一艺人垄断候选列表"))
        assertTrue(prompt.contains("优先发掘同风格、同流派的其他优秀音乐人作品"))
    }

    @Test fun playlistPermissionsAndSearchRoutingAreListedAsShortItems() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("  - 综合歌单：可添加和移除成员；不能重命名，不能删除。"))
        assertTrue(prompt.contains("  - 手机存储、订阅、远端和派生歌单：不能增删成员、不能重命名、不能删除"))
        assertTrue(prompt.contains("- 一级：当前使用且已确认音乐会员的主音源。"))
        assertTrue(prompt.contains("禁止静默换源"))
        // 开发侧红线不进入运行时提示词
        assertFalse(prompt.contains("公共接口"))
    }

    @Test fun generatedCardsAreSpecifiedBelowReplyText() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("生成的卡片（歌曲长卡、临时歌单或音源歌单卡片）在客户端固定挂载在本次回复正文的下方展示"))
        assertTrue(prompt.contains("若在正文中指引用户点击或查看卡片，须使用“下方”“下面的卡片/歌单”等准确方位"))
        assertTrue(prompt.contains("不得误称为“上方”"))
    }

    @Test fun conciseReplyWithoutEmptyGreetingsOrDuplicatedTrackLists() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("禁止“好的”“为你推荐如下”等空洞客套开场白，直接开门见山"))
        assertTrue(prompt.contains("正文禁止机械抄写或重复罗列卡片已有的曲目清单"))
    }

}
