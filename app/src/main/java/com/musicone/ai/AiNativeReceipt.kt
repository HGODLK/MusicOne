package com.musicone

import org.json.JSONObject

/** 收据来自原生事务，稳定身份使崩溃恢复不会重复展示，也不能被重新生成覆盖。 */
internal fun aiNativeReceipt(result: JSONObject, confirmed: Boolean): AiChatMessageUi {
    val id = "native-" + result.optString("operationId", result.optString("planId"))
    val details = if (confirmed) aiMetadataDetails(result) + result.optJSONArray("changes")?.objects().orEmpty().map { change ->
        when (change.optString("action")) {
            "create" -> "已新建本地歌单「${change.optString("oldTitle")}」，添加 ${change.optInt("addCount")} 首"
            "delete" -> "已删除本地歌单「${change.optString("oldTitle")}」，歌曲记录和实际文件保留"
            "restore" -> "已恢复本地歌单「${change.optString("oldTitle")}」及原成员关系"
            else -> change.optional("newTitle")?.let { "${change.optString("oldTitle")} → $it" }
            ?: "${change.optString("oldTitle")}：新增 ${change.optInt("addCount")} 首，跳过 ${change.optInt("duplicateCount")} 首重复，移除 ${change.optInt("removeCount")} 首歌单成员"
        }
    } else emptyList()
    return AiChatMessageUi(id, "assistant", if (confirmed) "操作已完成。" else "已取消，没有保存修改。",
        result = details.joinToString("\n\n").takeIf(String::isNotBlank), isNativeReceipt = true,
        receipt = if (confirmed) AiReceiptUi(aiChangeSummary(result, true), aiChangeGroups(result)) else null)
}

internal fun aiFailedNativeReceipt(planId: String, reason: String) = AiChatMessageUi("native-$planId", "assistant", "本批修改未执行。",
    isNativeReceipt = true, receipt = AiReceiptUi("成功 0 批，失败 1 批。原因：$reason", emptyList()))
