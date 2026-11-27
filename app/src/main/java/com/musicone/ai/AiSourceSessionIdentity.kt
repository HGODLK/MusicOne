package com.musicone

import com.musicone.source.sdk.SourceAccountState
import org.json.JSONObject

/** 刷新昵称、头像或会员信息不算换号；只有账号 ID、登录状态或会话版本变了才算失效。 */
internal fun aiSameSourceSession(previous: SourceAccountState?, current: SourceAccountState?): Boolean =
    previous?.account?.id == current?.account?.id && previous?.status == current?.status && previous?.revision == current?.revision

/** 这一轮搜索锁死开始时的主音源，中途切界面不影响本轮，下一轮提问再重新读。 */
internal fun aiSearchMainSource(checkpoint: JSONObject, currentSource: () -> String): String {
    val turn = checkpoint.optString("activeTurnId")
    if (checkpoint.optString("searchMainTurnId") != turn || !checkpoint.has("searchMainSourceId")) {
        checkpoint.put("searchMainTurnId", turn).put("searchMainSourceId", currentSource())
    }
    return checkpoint.getString("searchMainSourceId")
}
