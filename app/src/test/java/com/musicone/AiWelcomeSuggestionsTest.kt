package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class AiWelcomeSuggestionsTest {
    private val pool = aiWelcomeSuggestionPool

    private fun at(month: Int, day: Int, hour: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(2026, month, day, hour, 30) }

    private fun normalized(text: String) = text.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

    @Test fun poolIsLargeAndEveryTimeSlotIsCovered() {
        assertTrue(pool.size >= 1400)
        AiWelcomeCategory.entries.forEach { category -> assertTrue("$category 为空", pool.any { it.category == category }) }
        AiWelcomeSlot.entries.forEach { slot ->
            val count = pool.count { it.category == AiWelcomeCategory.TIME && !it.weekendOnly && slot in it.slots }
            assertTrue("$slot 时段句只有 $count 条", count >= 35)
        }
        assertTrue(pool.filter { it.category == AiWelcomeCategory.TIME }.all { it.slots.isNotEmpty() })
    }

    @Test fun textsAreUniqueShortAndNotNearDuplicates() {
        val seen = mutableMapOf<String, String>()
        pool.forEach {
            val key = normalized(it.text)
            assertFalse("重复：${it.text} / ${seen[key]}", key in seen)
            seen[key] = it.text
            assertTrue("过长：${it.text}", it.text.codePointCount(0, it.text.length) <= 40)
        }
        val grams = pool.map { item -> normalized(item.text).let { n -> (0 until n.length - 1).mapTo(HashSet()) { n.substring(it, it + 2) } } }
        for (i in grams.indices) for (j in i + 1 until grams.size) {
            val union = grams[i].size + grams[j].size - grams[i].count { it in grams[j] }
            val similarity = grams[i].count { it in grams[j] }.toDouble() / union
            assertTrue("过于相似：${pool[i].text} / ${pool[j].text}", similarity < 0.75)
        }
    }

    @Test fun noPromptAsksForDataTheAssistantCannotRead() {
        // 没有播放历史、年代与热度数据；也不用“收藏夹”这种不存在的叫法
        val unsupported = listOf("收藏夹", "我的收藏", "收藏里", "最近常听", "听歌记录", "播放历史", "最近一周",
            "占比最多", "播放次数", "最常听", "热度")
        // 工作学习类只保留“歇一歇”，不出现具体工作学习任务
        val workload = listOf("开会", "报告", "写代码", "作业", "刷题", "复习", "工位", "办公", "邮件", "论文", "PPT",
            "背单词", "自习", "赶工", "赶稿")
        val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "星期", "工作日")
        pool.forEach { item -> (unsupported + workload + weekdays).forEach { assertFalse("${item.text} 含 $it", item.text.contains(it)) } }
        pool.filter { it.text.contains("最近") || it.text.contains("今年") }.forEach { assertTrue("需要联网：${it.text}", it.needsWeb) }
    }

    @Test fun timeAndSeasonWordsMatchTheirTags() {
        val slotRules = mapOf(
            listOf("深夜", "凌晨", "失眠", "半夜", "午夜", "熬夜", "夜猫子") to setOf(AiWelcomeSlot.EVENING, AiWelcomeSlot.LATE_NIGHT),
            listOf("今晚", "晚上", "夜晚", "夜里", "睡前", "入睡", "助眠", "催眠", "晚风", "夜景", "夜色", "夜跑", "夜宵", "夜市", "夜路", "夜间", "夜班")
                to setOf(AiWelcomeSlot.DUSK, AiWelcomeSlot.EVENING, AiWelcomeSlot.LATE_NIGHT),
            listOf("早晨", "清晨", "早安", "早上", "晨跑", "晨间", "早餐", "晨光", "起床", "早起", "上午", "晨雾", "早饭", "早高峰")
                to setOf(AiWelcomeSlot.DAWN, AiWelcomeSlot.MORNING),
            listOf("午休", "午后", "下午", "午餐", "午间", "午睡", "午饭", "中午") to setOf(AiWelcomeSlot.NOON, AiWelcomeSlot.AFTERNOON),
            listOf("黄昏", "日落", "傍晚", "晚霞", "下班", "放学", "夕阳", "晚饭") to setOf(AiWelcomeSlot.AFTERNOON, AiWelcomeSlot.DUSK)
        )
        val seasonRules = mapOf(
            listOf("春天", "春日", "春风", "春雨", "踏青") to AiWelcomeSeason.SPRING,
            listOf("夏天", "夏日", "盛夏", "夏夜", "夏季") to AiWelcomeSeason.SUMMER,
            listOf("秋天", "秋日", "深秋", "秋风", "秋季", "落叶") to AiWelcomeSeason.AUTUMN,
            listOf("冬天", "冬日", "冬夜", "下雪", "初雪", "雪天", "冬季", "雪景") to AiWelcomeSeason.WINTER
        )
        val problems = mutableListOf<String>()
        pool.forEach { item ->
            // 艺人名里的字眼（如“夏日入侵企画”）不算时间描述
            val text = item.artist?.let { item.text.replace(it, "") } ?: item.text
            slotRules.forEach { (words, allowed) ->
                if (words.any { it in text } && (item.slots.isEmpty() || !allowed.containsAll(item.slots))) problems += "时段标签不符：${item.text} ${item.slots}"
            }
            seasonRules.forEach { (words, season) ->
                if (words.any { it in text } && item.seasons != setOf(season)) problems += "季节标签不符：${item.text}"
            }
            if (("周末" in text) != item.weekendOnly) problems += "周末标签不符：${item.text}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun eachArtistAppearsOnceAndPointsToOtherArtists() {
        val artists = pool.filter { it.category == AiWelcomeCategory.ARTIST }
        assertTrue(artists.size >= 400)
        assertTrue(artists.all { it.artist != null })
        assertEquals(artists.size, artists.mapTo(HashSet()) { it.artist!!.lowercase() }.size)
        val others = Regex("还有谁|其他|类似|相近|同类|相似|同路")
        artists.forEach {
            assertTrue("未包含艺人名：${it.text}", it.text.contains(it.artist!!))
            assertTrue("没有引导到其他艺人：${it.text}", others.containsMatchIn(it.text))
        }
        // 其他大类不登记艺人
        assertTrue(pool.filter { it.category != AiWelcomeCategory.ARTIST }.all { it.artist == null })
    }

    @Test fun pickedBubblesFitTheMomentAndHaveDistinctCategories() {
        val random = Random(7)
        for (month in 0..11) for (day in 1..7) for (hour in 0..23) for (web in listOf(true, false)) {
            val now = at(month, day, hour)
            val moment = aiWelcomeMoment(now)
            val picks = aiWelcomeSuggestions(now, web, random)
            assertEquals(AI_WELCOME_BUBBLE_COUNT, picks.size)
            assertEquals(1, picks.count { it.category == AiWelcomeCategory.TIME })
            assertEquals(picks.size, picks.map { it.category }.toSet().size)
            assertTrue(picks.all { it.fits(moment, web) })
            if (!web) assertTrue(picks.none { it.needsWeb })
        }
    }

    @Test fun momentUsesLocalHourMonthAndWeekend() {
        val saturday = (1..7).map { at(Calendar.NOVEMBER, it, 0) }.first { it.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY }
        val monday = (saturday.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }
        assertEquals(AiWelcomeMoment(AiWelcomeSlot.LATE_NIGHT, AiWelcomeSeason.AUTUMN, true), aiWelcomeMoment(saturday))
        assertFalse(aiWelcomeMoment(monday).weekend)
        assertEquals(AiWelcomeSlot.DUSK, aiWelcomeMoment(at(Calendar.JANUARY, 5, 17)).slot)
        assertEquals(AiWelcomeSeason.WINTER, aiWelcomeMoment(at(Calendar.DECEMBER, 5, 12)).season)
    }

    @Test fun turningWebOffReplacesOnlyWebBubbles() {
        val now = at(Calendar.MARCH, 10, 15)
        val web = pool.first { it.needsWeb }
        val keep = aiWelcomeSuggestions(now, false, Random(3)).filter { it.category != web.category }.take(3)
        val replaced = aiWelcomeReplaceWebSuggestions(keep + web, now, Random(5))
        assertEquals(keep, replaced.take(3))
        assertFalse(replaced.last().needsWeb)
        assertEquals(web.category, replaced.last().category)
        assertEquals(4, replaced.map { it.text }.toSet().size)
        assertEquals(keep, aiWelcomeReplaceWebSuggestions(keep, now))
    }

    @Test fun nightHeadingsOnlyShowAtNight() {
        val random = Random(11)
        repeat(300) {
            val heading = aiWelcomeHeading(at(Calendar.MAY, 6, 9), random)
            assertTrue(heading.slots.isEmpty() || AiWelcomeSlot.MORNING in heading.slots)
            assertFalse(heading.subtitle.contains("收藏夹"))
        }
        assertTrue(aiWelcomeHeadings.any { AiWelcomeSlot.LATE_NIGHT in it.slots })
    }
}
