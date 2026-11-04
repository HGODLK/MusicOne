package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostSearchResultTest {
    @Test
    fun zeroQqMidIsRejectedBeforeItCanBecomeADuplicateLazyGridKey() {
        assertEquals(false, isValidQqTrackMid("0"))
        assertEquals(false, isValidQqTrackMid(""))
        assertEquals(true, isValidQqTrackMid("002WBZaC2wsLqC"))
    }

}
