package com.musicone.source.qq.internal

import android.content.Context
import java.util.UUID

/** 名称与字段延续旧版；传入的Context由宿主按来源/签名隔离。 */
internal class PlatformPreferences(context: Context) {
    val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    fun readSession(source: MusicSource): PlatformSession {
        require(source == MusicSource.QQ)
        val credential = preferences.getString("session_qq_credential", "").orEmpty()
        val device = preferences.getString("session_qq_device_id", "").orEmpty().ifBlank {
            UUID.randomUUID().toString().also { check(preferences.edit().putString("session_qq_device_id", it).commit()) }
        }
        val userId = preferences.getString("session_qq_user_id", "").orEmpty()
        val account = if (credential.isNotBlank() && userId.isNotBlank()) MusicAccount(MusicSource.QQ, userId,
            preferences.getString("session_qq_nickname", "").orEmpty(), preferences.getString("session_qq_avatar_url", null),
            preferences.getBoolean("session_qq_vip_access", false) && preferences.getInt("session_qq_membership_version", 0) == 1,
            preferences.getString("session_qq_background_url", null), preferences.getString("session_qq_signature", "").orEmpty(),
            preferences.getInt("session_qq_follows", 0), preferences.getInt("session_qq_followers", 0)) else null
        QqEntitlements.activate(credential)
        return PlatformSession(source, credential, device, account)
    }
    fun credential(source: MusicSource): String = readSession(source).credential
    fun saveSession(credential: String, account: MusicAccount) {
        val previous = preferences.getString("session_qq_credential", "").orEmpty()
        val editor = preferences.edit().putString("session_qq_credential", credential)
            .putString("session_qq_user_id", account.userId).putString("session_qq_nickname", account.nickname)
            .putString("session_qq_avatar_url", account.avatarUrl).putBoolean("session_qq_vip_access", account.hasVipAccess)
            .putInt("session_qq_membership_version", 1).putString("session_qq_background_url", account.backgroundUrl)
            .putString("session_qq_signature", account.signature).putInt("session_qq_follows", account.follows).putInt("session_qq_followers", account.followers)
        check(editor.commit()) { "QQ会话保存失败" }
        if (previous != credential) QqEntitlements.forget(previous)
        QqEntitlements.activate(credential)
    }
    fun clearSession(source: MusicSource) {
        require(source == MusicSource.QQ)
        val previous = credential(source)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("session_qq_") && it != "session_qq_device_id" }.forEach(editor::remove)
        // 空键用于保留用户主动退出的选择，禁止旧数据再次导入覆盖。
        check(editor.putString("session_qq_credential", "").commit())
        QqEntitlements.forget(previous)
    }
}
