package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/** 繁体备用句子池：规模、唯一性、艺人约束与抽取规则与简体版一致。 */
class AiWelcomeSuggestionsTwTest {
    private val pool = aiWelcomeSuggestionPoolTw

    @Test fun poolIsLargeUniqueAndCoversEverySlot() {
        assertTrue("总数 ${pool.size}", pool.size >= 1400)
        AiWelcomeCategory.entries.forEach { c -> assertTrue("$c 为空", pool.any { it.category == c }) }
        AiWelcomeSlot.entries.forEach { s ->
            assertTrue("$s", pool.count { it.category == AiWelcomeCategory.TIME && s in it.slots } >= 35)
        }
        val keys = pool.map { it.text.filter { c -> c.isLetterOrDigit() || c in '一'..'鿿' }.lowercase() }
        val dups = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("重複：$dups", dups.isEmpty())
        assertTrue("過長", pool.all { it.text.length <= 40 })
    }

    @Test fun artistsAreUniqueAndPointToOthers() {
        val artists = pool.filter { it.category == AiWelcomeCategory.ARTIST }
        assertTrue("藝人 ${artists.size}", artists.size >= 400)
        assertEquals(artists.size, artists.mapTo(HashSet()) { it.artist!!.lowercase() }.size)
        artists.forEach { assertTrue(it.text, it.text.contains(it.artist!!)) }
    }

    @Test fun pickedBubblesFollowTheSameRules() {
        val random = Random(13)
        for (month in 0..11) for (hour in 0..23) for (web in listOf(true, false)) {
            val now = Calendar.getInstance().apply { clear(); set(2026, month, 3, hour, 0) }
            val picks = aiWelcomeSuggestions(now, web, random, pool)
            assertEquals(AI_WELCOME_BUBBLE_COUNT, picks.size)
            assertEquals(picks.size, picks.map { it.category }.toSet().size)
            assertTrue(picks.all { it.fits(aiWelcomeMoment(now), web) })
        }
    }
}
