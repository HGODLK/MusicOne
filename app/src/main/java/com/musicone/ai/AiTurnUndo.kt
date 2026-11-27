package com.musicone

import org.json.JSONObject

/** 快捷撤销只挂在执行操作的那一轮回答下面；旧数据通过最后一轮提问后的真实收据来兼容。 */
internal fun aiUndoForCurrentTurn(operation: String?, checkpoint: JSONObject, messages: List<AiChatMessageUi>): String? {
    if (operation == null) return null
    checkpoint.optional("undoTurnId")?.let { turn ->
        return operation.takeIf { turn == checkpoint.optional("activeTurnId") }
    }
    val lastUser = messages.indexOfLast { it.role == "user" }
    return operation.takeIf { lastUser >= 0 && messages.drop(lastUser + 1).any { it.isNativeReceipt && it.id == "native-$operation" } }
}
