package com.musicone

import org.json.JSONObject

/** 分组回执只使用事务实际保存的差异，不让模型重新猜测改动或成功数量。 */
internal fun aiChangeGroups(data: JSONObject): List<AiChangeGroupUi> {
    val metadata = data.optJSONArray("trackActual") ?: data.optJSONArray("trackChanges")
    return metadata?.objects().orEmpty().map { edit ->
        AiChangeGroupUi(edit.optString("title"), edit.getJSONArray("fields").objects().map { field ->
            val label = when (field.optString("field")) { "title" -> "歌名"; "artists" -> "艺人"; else -> "专辑" }
            AiFieldChangeUi(label, field.optString("oldDisplay"),
                if (field.isNull("new")) "恢复原始资料" else field.optString("new"))
        })
    } + data.optJSONArray("changes")?.objects().orEmpty().map { change ->
        AiChangeGroupUi(change.optString("oldTitle"), buildList {
            when (change.optString("action")) {
                "create" -> add(AiFieldChangeUi("新建本地歌单", "尚未创建", change.optString("oldTitle")))
                "delete" -> add(AiFieldChangeUi("删除本地歌单", change.optString("oldTitle"), "仅移除歌单及成员关系，保留歌曲和文件"))
                "restore" -> add(AiFieldChangeUi("恢复本地歌单", "已删除", change.optString("oldTitle")))
            }
            change.optJSONArray("songSnapshot")?.objects().orEmpty().forEach { song ->
                val title = song.optional("title_override") ?: JSONObject(song.getString("data")).optString("title")
                add(AiFieldChangeUi(if (change.optString("action") == "restore") "恢复成员关系" else "移除成员关系", title, "保留歌曲记录和文件"))
            }
            change.optional("newTitle")?.let { add(AiFieldChangeUi("歌单名称", change.optString("oldTitle"), it)) }
            change.optJSONArray("tracks")?.objects().orEmpty().forEach { add(AiFieldChangeUi("添加歌曲", "未加入", it.optString("title"))) }
            change.optJSONArray("remove")?.objects().orEmpty().forEach { add(AiFieldChangeUi(if (data.optString("kind") == "remove") "移除成员关系" else "撤销添加", it.optString("title"), "移出该歌单，保留歌曲和文件")) }
            change.optJSONArray("restoreMembers")?.objects().orEmpty().forEach { add(AiFieldChangeUi("恢复成员关系", "已移出", it.optString("title"))) }
        })
    } + aiLyricChangeGroups(data)
}

internal fun aiChangeSummary(data: JSONObject, executed: Boolean): String {
    // 歌词计划单独成批，摘要里带上来源准确性提醒。
    aiLyricChangeSummary(data, executed)?.let { return it }
    val tracks = (data.optJSONArray("trackActual") ?: data.optJSONArray("trackChanges"))?.length() ?: 0
    val changes = data.optJSONArray("changes")?.objects().orEmpty()
    val added = changes.sumOf { it.optInt("addCount", (it.optJSONArray("tracks")?.length() ?: 0) + (it.optJSONArray("restoreMembers")?.length() ?: 0)) }
    val removed = changes.sumOf { it.optInt("removeCount", it.optJSONArray("remove")?.length() ?: 0) }
    val renamed = changes.count { it.has("newTitle") }
    val skipped = data.optInt("skippedCount") + changes.sumOf { it.optInt("duplicateCount") }
    val verb = if (executed) "已" else "将"
    val operations = buildList {
        val created = changes.count { it.optString("action") == "create" }
        val deleted = changes.count { it.optString("action") == "delete" }
        val restored = changes.count { it.optString("action") == "restore" }
        if (created > 0) add("${verb}新建 $created 个本地歌单")
        if (deleted > 0) add("${verb}删除 $deleted 个本地歌单（歌曲和文件保留）")
        if (restored > 0) add("${verb}恢复 $restored 个本地歌单")
        if (tracks > 0) add("${verb}修改 $tracks 首")
        if (renamed > 0) add("${verb}重命名 $renamed 个歌单")
        if (added > 0) add("${verb}添加 $added 首")
        if (removed > 0) add(if (data.optString("kind") == "remove") "${verb}移除 $removed 首歌单成员" else "${verb}撤销添加 $removed 首")
    }.ifEmpty { listOf(if (executed) "操作已完成" else "待确认修改") }
    return operations.joinToString("，") + "；跳过 $skipped 项" + if (executed) "，失败 0 项。" else "。"
}
