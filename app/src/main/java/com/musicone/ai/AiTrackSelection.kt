package com.musicone

import org.json.JSONObject

/** 整批选择使用已返回的本机结果句柄，逐首选择继续使用真实歌曲引用。 */
internal data class AiTrackSelection(val trackRefs: List<String>, val resultHandles: List<String>) {
    companion object {
        fun parse(arguments: JSONObject): AiTrackSelection {
            fun strings(key: String): List<String> {
                if (!arguments.has(key)) return emptyList()
                val values = arguments.getJSONArray(key)
                return (0 until values.length()).map { index ->
                    val value = values.get(index)
                    require(value is String && value.isNotBlank()) { "歌曲选择必须使用真实非空引用" }
                    value
                }.distinct()
            }
            val refs = strings("trackRefs")
            val handles = strings("resultHandles")
            require(refs.isNotEmpty() xor handles.isNotEmpty()) { "请提供trackRefs或resultHandles其中一种非空选择" }
            return AiTrackSelection(refs, handles)
        }
    }
}

internal suspend fun AiLibraryTools.resolveSelection(arguments: JSONObject): List<MusicTrack> {
    val selection = AiTrackSelection.parse(arguments)
    return if (selection.trackRefs.isNotEmpty()) resolveTracks(selection.trackRefs) else resolveResultHandles(selection.resultHandles)
}
