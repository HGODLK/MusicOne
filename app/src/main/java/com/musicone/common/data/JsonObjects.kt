package com.musicone

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.jsonObjects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }
