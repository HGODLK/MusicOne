package com.musicone.sourceintegration

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** 旧身份字符串只用于读取 0.2.0 数据；仅交付给签名匹配的内置音源。 */
internal class LegacySourceDataMigration(
    private val hostContext: Context,
    private val trustedSigners: Map<String, String>,
) {
    @Synchronized
    fun migrate(sourceId: String, signerSha256: String, targetContext: Context) {
        val trusted = trustedSigners[sourceId] ?: return
        require(trusted.equals(signerSha256, ignoreCase = true)) { "旧音源数据接收方签名不匹配" }
        val migration = targetContext.getSharedPreferences("host_legacy_migration", Context.MODE_PRIVATE)
        if (migration.getInt("version", 0) >= VERSION) return
        when (sourceId) {
            "com.musicone.source.qq" -> {
                migrateMainSession("qq", targetContext)
                listOf("qq_daily_mix", "qq_search", "qq_playlist_sort", "qq_artwork_aliases")
                    .forEach { copyPreferences(it, targetContext) }
                val quality = hostContext.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)
                val targetQuality = targetContext.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)
                if (!targetQuality.contains("preferred_qq")) {
                    copyValues(quality.all.filterKeys { it == "preferred_qq" }, targetQuality)
                }
            }
            "com.musicone.source.kugou" -> migrateMainSession("kugou", targetContext)
            "com.musicone.source.netease" -> {
                migrateMainSession("netease", targetContext)
                File(hostContext.filesDir, "profile").listFiles().orEmpty()
                    .filter { it.isFile && it.name.startsWith("netease_") && it.extension == "webp" }
                    .forEach { copyFileIfMissing(it, File(targetContext.filesDir, "profile/${it.name}")) }
            }
            "com.musicone.source.bilibili" -> {
                copyPreferences("musicone_bilibili_session", targetContext)
                copyPreferences("musicone_bili_device", targetContext)
            }
            else -> return
        }
        // 所有写入完成后才记录完成；中断时重试，原数据始终保留，不访问网络。
        check(migration.edit().putInt("version", VERSION).commit()) { "无法保存旧音源迁移状态" }
    }

    private fun migrateMainSession(legacyId: String, targetContext: Context) {
        val source = hostContext.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val target = targetContext.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val prefix = "session_${legacyId}_"
        // 已存在的凭证键（包括用户主动退出后的空值）具有优先权，不能混入旧账号资料。
        if (target.contains("${prefix}credential")) return
        val values = source.all.filterKeys { it.startsWith(prefix) }.toMutableMap()
        if (legacyId == "netease" && !values.containsKey("${prefix}credential")) {
            mapOf("netease_cookie" to "credential", "netease_device_id" to "device_id",
                "user_id" to "user_id", "nickname" to "nickname", "avatar_url" to "avatar_url")
                .forEach { (old, field) -> source.all[old]?.let { values["$prefix$field"] = it } }
        }
        copyValues(values, target)
    }

    private fun copyPreferences(name: String, targetContext: Context) {
        val target = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        // 包可能在较早版本已经写入整组会话，不能把两个账号的字段拼接起来。
        if (target.all.isNotEmpty()) return
        copyValues(hostContext.getSharedPreferences(name, Context.MODE_PRIVATE).all, target)
    }

    private fun copyValues(values: Map<String, *>, target: SharedPreferences) {
        if (values.isEmpty()) return
        val editor = target.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> Unit
                else -> error("旧偏好包含不支持的数据类型")
            }
        }
        check(editor.commit()) { "旧音源数据写入失败，保留原始数据等待重试" }
        check(values.all { (key, value) -> target.all[key] == value }) { "旧音源数据写入校验失败" }
    }

    private fun copyFileIfMissing(source: File, target: File) {
        if (target.exists()) return
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.migration")
        source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        check(temporary.length() == source.length() && temporary.renameTo(target)) { "旧背景文件迁移失败" }
    }

    private companion object { const val VERSION = 1 }
}
