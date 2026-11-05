package com.musicone

import com.musicone.source.sdk.SourceAccountState
import org.json.JSONObject

/** 昵称、头像和会员资料刷新不等于换号；身份、登录状态或会话代次改变仍必须拦截。 */
internal fun aiSameSourceSession(previous: SourceAccountState?, current: SourceAccountState?): Boolean =
    previous?.account?.id == current?.account?.id && previous?.status == current?.status && previous?.revision == current?.revision

/** 本轮搜索的主音源快照不跟随界面切换，下一问重新读取当前选择。 */
internal fun aiSearchMainSource(checkpoint: JSONObject, currentSource: () -> String): String {
    val turn = checkpoint.optString("activeTurnId")
    if (checkpoint.optString("searchMainTurnId") != turn || !checkpoint.has("searchMainSourceId")) {
        checkpoint.put("searchMainTurnId", turn).put("searchMainSourceId", currentSource())
    }
    return checkpoint.getString("searchMainSourceId")
}
