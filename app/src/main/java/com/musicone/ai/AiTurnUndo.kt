package com.musicone

import org.json.JSONObject

/** 快捷撤销只属于执行操作的用户轮次，旧记录通过最后一问之后的真实收据迁移。 */
internal fun aiUndoForCurrentTurn(operation: String?, checkpoint: JSONObject, messages: List<AiChatMessageUi>): String? {
    if (operation == null) return null
    checkpoint.optional("undoTurnId")?.let { turn ->
        return operation.takeIf { turn == checkpoint.optional("activeTurnId") }
    }
    val lastUser = messages.indexOfLast { it.role == "user" }
    return operation.takeIf { lastUser >= 0 && messages.drop(lastUser + 1).any { it.isNativeReceipt && it.id == "native-$operation" } }
}
