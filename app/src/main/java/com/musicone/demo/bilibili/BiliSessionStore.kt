package com.musicone.demo

import android.content.Context
import android.content.SharedPreferences

/**
 * B站账号会话数据模型。
 *
 * @param cookie 用于 Web 播放和收藏夹等接口的 Cookie 串联字符串
 * @param mid B站用户 UID
 * @param name 昵称
 * @param avatar 头像图片 URL
 */
internal data class BiliAccountSession(
    val cookie: String,
    val mid: String,
    val name: String,
    val avatar: String = "",
)

/**
 * 独立的 B站会话持久化存储。
 *
 * 仅维护 B站补充音源专属的会话状态，绝不调用 PlatformPreferences.saveSession，
 * 绝不修改主音源设置或影响其他平台的账号状态。
 */
internal class BiliSessionStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * 读取当前存储的 B站会话信息。
     * 若未登录或缺少关键 Cookie/MID 则返回 null。
     */
    @Synchronized
    fun read(): BiliAccountSession? {
        val cookie = prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() } ?: return null
        val mid = prefs.getString(KEY_MID, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_NAME, "") ?: ""
        val avatar = prefs.getString(KEY_AVATAR, "") ?: ""
        return BiliAccountSession(
            cookie = cookie,
            mid = mid,
            name = name,
            avatar = avatar,
        )
    }

    /**
     * 保存 B站会话信息。
     */
    @Synchronized
    fun save(session: BiliAccountSession) {
        prefs.edit()
            .putString(KEY_COOKIE, session.cookie.trim())
            .putString(KEY_MID, session.mid.trim())
            .putString(KEY_NAME, session.name.trim())
            .putString(KEY_AVATAR, session.avatar.trim())
            .apply()
    }

    /**
     * 清除本地存储的 B站会话，退出登录。
     */
    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(KEY_COOKIE)
            .remove(KEY_MID)
            .remove(KEY_NAME)
            .remove(KEY_AVATAR)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "musicone_bilibili_session"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_MID = "mid"
        private const val KEY_NAME = "name"
        private const val KEY_AVATAR = "avatar"
    }
}
