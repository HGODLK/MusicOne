package com.musicone.sourceintegration

import com.musicone.MusicSource
import org.junit.Assert.*
import org.junit.Test

class RemovedSourceAccountDataTest {
    @Test fun removesOnlyTheRequestedAccountsLegacyFields() {
        val keys = setOf("session_qq_credential", "session_qq_device_id", "session_qq_nickname",
            "session_kugou_credential", "selected_source", "qq_daily_mix", "user_id")
        assertEquals(setOf("session_qq_credential", "session_qq_device_id", "session_qq_nickname"),
            removedSourceAccountKeys(MusicSource.QQ.id, keys))
    }

    @Test fun neteaseCannotRecoverItsAccountFromOlderUnprefixedFields() {
        val keys = setOf("session_netease_credential", "netease_cookie", "netease_device_id", "user_id",
            "nickname", "avatar_url", "session_qq_credential", "selected_source", "library_tracks")
        assertEquals(keys - setOf("session_qq_credential", "selected_source", "library_tracks"),
            removedSourceAccountKeys(MusicSource.NETEASE.id, keys))
    }
}
