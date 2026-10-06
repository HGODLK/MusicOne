package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTitleTest {
    @Test
    fun playerKeepsOnlyThePrimaryTitleWhenTrailingDetailsArePresent() {
        assertEquals("私、案山子。", playerPrimaryTitle("私、案山子。 (我、稻草人。)"))
        assertEquals("夜に駆ける", playerPrimaryTitle("夜に駆ける（ヨルニカケル）"))
        assertEquals("歌曲", playerPrimaryTitle("歌曲 (翻译)（カタカナ）"))
        assertEquals("夜に駆ける feat. 歌手", playerPrimaryTitle("夜に駆ける（ヨルニカケル） feat. 歌手"))
    }

    @Test
    fun playerDoesNotEraseAParenthesizedTitleOrInternalParentheses() {
        assertEquals("(I Can't Get No) Satisfaction", playerPrimaryTitle("(I Can't Get No) Satisfaction"))
        assertEquals("（无题）", playerPrimaryTitle("（无题）"))
    }
}
