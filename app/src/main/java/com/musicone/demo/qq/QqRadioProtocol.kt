package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

// 99 是账号猜你喜欢电台；省略编号会落入服务端默认电台。
internal fun qqRadioParameters(count: Int): JSONObject = JSONObject()
    .put("id", 99)
    .put("num", count.coerceIn(1, 20))
    .put("from", 0)
    .put("scene", 0)
    .put("song_ids", JSONArray())
