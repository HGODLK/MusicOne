package com.musicone.source.kugou

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouLibraryFeaturesTest {
    @Test fun favoriteAliasesIncludeBothGlobalKeysAndIgnoreMissingCreatorIdentity() {
        val public = kugouCollectionFavoriteKeys("public", JSONObject().put("global_collection_id", "g1"))
        val saved = kugouCollectionFavoriteKeys("saved", JSONObject().put("global_collection_id", 0).put("list_create_gid", "g1"))
        assertTrue(public.intersect(saved).contains("global:g1"))
        assertEquals(setOf("empty"), kugouCollectionFavoriteKeys("empty", JSONObject()
            .put("global_collection_id", 0).put("list_create_userid", 0).put("list_create_listid", 0)))
        assertTrue(kugouCollectionFavoriteKeys("copy", JSONObject().put("list_create_userid", 42)
            .put("list_create_listid", 8)).contains("creator:42:8"))
    }

}
