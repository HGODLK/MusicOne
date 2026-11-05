package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/** 英文备用句子池：结构与中文版一致，抽取规则同样成立。 */
class AiWelcomeSuggestionsEnTest {
    private val pool = aiWelcomeSuggestionPoolEn

    @Test fun poolIsLargeUniqueAndCoversEverySlot() {
        assertTrue("总数 ${pool.size}", pool.size >= 1400)
        AiWelcomeCategory.entries.forEach { c -> assertTrue("$c 为空", pool.any { it.category == c }) }
        AiWelcomeSlot.entries.forEach { s -> assertTrue("$s", pool.count { it.category == AiWelcomeCategory.TIME && s in it.slots } >= 35) }
        val keys = pool.map { it.text.lowercase().replace(Regex("[^a-z0-9]+"), "") }
        val dups = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("重复：$dups", dups.isEmpty())
        assertTrue("长度或汉字", pool.all { it.text.length <= 80 && !Regex("[\\u4e00-\\u9fff]").containsMatchIn(it.text) })
        pool.filter { Regex("(?i)lately|this year|latest").containsMatchIn(it.text) }.forEach { assertTrue(it.text, it.needsWeb) }
        pool.filter { it.artist == null && Regex("(?i)weekend").containsMatchIn(it.text) }.forEach { assertTrue("周末：${it.text}", it.weekendOnly) }
    }

    @Test fun artistsAreUniqueAndSpanishLanguageIsWellRepresented() {
        val artists = pool.filter { it.category == AiWelcomeCategory.ARTIST }
        assertTrue("艺人 ${artists.size}", artists.size >= 400)
        assertEquals(artists.size, artists.mapTo(HashSet()) { it.artist!!.lowercase() }.size)
        artists.forEach {
            assertTrue(it.text, it.text.contains(it.artist!!))
            assertTrue(it.text, Regex("(?i)similar|who else|other|like (him|her|that)").containsMatchIn(it.text))
        }
        assertTrue(artists.count { it.artist in setOf("Rosalía", "Bad Bunny", "Soda Stereo", "Celia Cruz", "Silvio Rodríguez") } == 5)
    }

    @Test fun pickedBubblesFollowTheSameRules() {
        val random = Random(9)
        for (month in 0..11) for (hour in 0..23) for (web in listOf(true, false)) {
            val now = Calendar.getInstance().apply { clear(); set(2026, month, 3, hour, 0) }
            val picks = aiWelcomeSuggestions(now, web, random, pool)
            assertEquals(AI_WELCOME_BUBBLE_COUNT, picks.size)
            assertEquals(picks.size, picks.map { it.category }.toSet().size)
            assertTrue(picks.all { it.fits(aiWelcomeMoment(now), web) })
            assertFalse(aiWelcomeHeading(now, random, aiWelcomeHeadingsEn).title.isBlank())
        }
    }
}
