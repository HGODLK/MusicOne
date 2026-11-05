package com.musicone

import com.musicone.source.sdk.SourceAccount
import com.musicone.source.sdk.SourceAccountState
import com.musicone.source.sdk.SourceSessionStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiInteractionContinuityTest {
    @Test fun keyboardDescentStopsAtPlayerTopWithoutAVisibilitySwitch() {
        listOf(0, 24, 48).forEach { navigation ->
            val playerTop = 78 + navigation
            (0..600).forEach { keyboard ->
                val consumed = maxOf(keyboard, navigation)
                val extra = aiBottomClearance(playerTop, keyboard, navigation)
                assertEquals(maxOf(playerTop, consumed), consumed + extra)
                if (keyboard > 0) {
                    val previous = maxOf(keyboard - 1, navigation) + aiBottomClearance(playerTop, keyboard - 1, navigation)
                    assertTrue(consumed + extra - previous in 0..1)
                }
            }
        }
    }

    @Test fun absentPlayerDoesNotAddAnotherSystemNavigationInset() {
        assertEquals(0, aiBottomClearance(24, 0, 24))
        assertEquals(0, aiBottomClearance(0, 0, 0))
        assertEquals(0, aiBottomClearance(24, 500, 24))
    }

    @Test fun shortcutUndoIsBoundToItsUserTurnAfterRestore() {
        val checkpoint = JSONObject().put("activeTurnId", "turn1").put("undoTurnId", "turn1")
        assertEquals("operation", aiUndoForCurrentTurn("operation", checkpoint, emptyList()))
        val restored = JSONObject(checkpoint.toString())
        assertEquals("operation", aiUndoForCurrentTurn("operation", restored, emptyList()))
        restored.put("activeTurnId", "turn2")
        assertNull(aiUndoForCurrentTurn("operation", restored, emptyList()))
    }

    @Test fun oldConversationCannotRestoreAnUndoFromBeforeTheLatestQuestion() {
        val question = AiChatMessageUi("question", "user", "修改资料")
        val receipt = AiChatMessageUi("native-operation", "assistant", "已完成", isNativeReceipt = true)
        assertEquals("operation", aiUndoForCurrentTurn("operation", JSONObject(), listOf(question, receipt)))
        assertNull(aiUndoForCurrentTurn("operation", JSONObject(), listOf(question, receipt, question.copy(id = "next", text = "聊点别的"))))
        assertNull(aiUndoForCurrentTurn("other", JSONObject(), listOf(question, receipt)))
        assertNull(aiUndoForCurrentTurn(null, JSONObject(), listOf(question, receipt)))
    }

    @Test fun consecutiveDisclosureMessagesUseOneSpacingWhileQuestionsStaySeparate() {
        val assistant = AiChatMessageUi("a", "assistant", "", reasoning = "思考")
        val receipt = AiChatMessageUi("receipt", "assistant", "已完成", isNativeReceipt = true)
        val user = AiChatMessageUi("u", "user", "问题")
        assertEquals(0, aiChatMessageGap(null, assistant))
        assertEquals(0, aiChatMessageGap(assistant, receipt))
        assertEquals(24, aiChatMessageGap(receipt, user))
        assertEquals(24, aiChatMessageGap(user, assistant))
    }

    @Test fun refreshingAccountDisplayAndMembershipDoesNotInvalidateSearch() {
        val old = SourceAccountState(SourceSessionStatus.AUTHENTICATED, SourceAccount("id", "旧昵称", membership = "unknown"), 5)
        val fresh = old.copy(account = old.account!!.copy(displayName = "新昵称", avatarUrl = "avatar", membership = "vip", follows = 10))
        assertTrue(aiSameSourceSession(old, fresh))
        assertTrue(aiSameSourceSession(null, null))
    }

    @Test fun actualAccountLogoutAndSessionReplacementStillInvalidateResults() {
        val old = SourceAccountState(SourceSessionStatus.AUTHENTICATED, SourceAccount("id", "账号"), 5)
        assertFalse(aiSameSourceSession(old, old.copy(account = old.account!!.copy(id = "other"))))
        assertFalse(aiSameSourceSession(old, old.copy(revision = 6)))
        assertFalse(aiSameSourceSession(old, old.copy(status = SourceSessionStatus.EXPIRED)))
        assertFalse(aiSameSourceSession(old, null))
    }

    @Test fun inFlightSourceSelectionSurvivesUiSwitchAndRefreshesForTheNextQuestion() {
        val checkpoint = JSONObject().put("activeTurnId", "turn1")
        assertEquals("qq", aiSearchMainSource(checkpoint) { "qq" })
        assertEquals("qq", aiSearchMainSource(checkpoint) { error("本轮不应重新读取界面选择") })
        val restored = JSONObject(checkpoint.toString())
        assertEquals("qq", aiSearchMainSource(restored) { "kugou" })
        restored.put("activeTurnId", "turn2")
        assertEquals("kugou", aiSearchMainSource(restored) { "kugou" })
    }
}
