package com.musicone

import org.junit.Assert.*
import org.junit.Test

class AiConversationTitleTest {
    @Test fun completeFirstQuestionBecomesTheTitleSeed() {
        assertEquals("推荐适合夜跑的歌曲。再帮我保存歌单。", aiTitleQuestion("  推荐适合夜跑的歌曲。再帮我保存歌单。  "))
        assertEquals("整理我的歌单！然后播放", aiTitleQuestion("整理我的歌单！然后播放"))
        assertEquals("分析听歌偏好\n这是后续说明", aiTitleQuestion("分析听歌偏好\n这是后续说明"))
        val longQuestion = "完整要求。".repeat(100)
        assertEquals(longQuestion, aiTitleQuestion(longQuestion))
    }

    @Test fun titleDropsPrefixQuotesAndFollowingExplanation() {
        assertEquals("夜跑歌单", aiNormalizeConversationTitle("\n标题：\"夜跑歌单\"\n说明：概括了用户的诉求"))
        assertEquals("收藏整理", aiNormalizeConversationTitle("标题: **收藏整理**"))
        assertEquals("", aiNormalizeConversationTitle("  \n  "))
    }

    @Test fun unicodeTitleUsesTwelveCodePointsWithoutSplittingEmoji() {
        val result = aiNormalizeConversationTitle("🎵".repeat(15))
        assertEquals(12, result.codePointCount(0, result.length))
        assertEquals("🎵".repeat(11) + "…", result)
        val mixed = aiNormalizeConversationTitle("夜跑" + "🎵".repeat(15))
        assertEquals(12, mixed.codePointCount(0, mixed.length))
        assertEquals("夜跑" + "🎵".repeat(9) + "…", mixed)
        assertFalse(Character.isHighSurrogate(mixed.last()))
    }

    @Test fun titleAtLimitStaysWholeAndLongTitleGetsEllipsis() {
        assertEquals("0123456789ab", aiNormalizeConversationTitle("0123456789ab"))
        assertEquals("0123456789a…", aiNormalizeConversationTitle("0123456789abcdef"))
    }
}
