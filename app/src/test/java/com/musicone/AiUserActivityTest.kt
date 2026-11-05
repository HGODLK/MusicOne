package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiUserActivityTest {
    @Test fun onlyActualUserSendsDetermineActivityAndPersistence() {
        val first = AiChatMessageUi("first", "user", "完整问题。补充要求", sentAt = 100)
        val next = AiChatMessageUi("next", "user", "继续", sentAt = 200)
        val reply = AiChatMessageUi("reply", "assistant", "回答", sentAt = 999)
        assertNull(aiFirstUserQuestion(listOf(reply), emptyList()))
        assertEquals(first.text, aiFirstUserQuestion(listOf(first, reply, next), emptyList()))
        assertEquals(200L, aiLastUserSentAt(listOf(first, reply, next), emptyList()))
        assertEquals(100L, aiLastUserSentAt(listOf(first, reply.copy(text = "更新回复")), emptyList()))
        assertEquals(0L, aiLastUserSentAt(listOf(reply), emptyList()))
    }
    @Test fun restorePromptsDoNotCreateHumanMessagesAndProtocolCarriesSendTime() {
        val restore = JSONObject().put("role", "user").put("content", "对话从断点恢复，请先read_task读取完整任务与约束。")
        assertNull(aiFirstUserQuestion(emptyList(), listOf(restore)))
        val user = JSONObject().put("role", "user").put("content", "我的首问").put("musiconeTurnId", "turn").put("musiconeSentAt", 300L)
        assertEquals("我的首问", aiFirstUserQuestion(emptyList(), listOf(restore, user)))
        assertEquals(300L, aiLastUserSentAt(emptyList(), listOf(restore, user)))
    }
}
