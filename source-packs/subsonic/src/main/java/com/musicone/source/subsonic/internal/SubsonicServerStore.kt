package com.musicone.source.subsonic.internal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 已保存服务器的持久化；所有写入同步提交，成功后才更新内存和版本号。 */
internal class SubsonicServerStore(context: Context) {
    private val preferences = context.getSharedPreferences("subsonic_servers", Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var snapshot: Snapshot = read()

    data class Snapshot(val servers: List<SubsonicServer>, val currentKey: String?, val revision: Long) {
        val current: SubsonicServer? get() = servers.firstOrNull { it.key == currentKey }
    }

    fun snapshot(): Snapshot = snapshot
    fun server(key: String): SubsonicServer? = snapshot.servers.firstOrNull { it.key == key }

    /** 保存或替换同一身份的服务器，并切换为当前服务器。 */
    fun saveAndSelect(server: SubsonicServer) = update { state ->
        val others = state.servers.filterNot { it.key == server.key }
        Snapshot(others + server, server.key, state.revision + 1)
    }

    fun select(key: String) = update { state ->
        require(state.servers.any { it.key == key }) { "该服务器已被删除" }
        if (state.currentKey == key) state else state.copy(currentKey = key, revision = state.revision + 1)
    }

    /** 删除当前服务器时不自动切到别的服务器，避免用户在不知情时换了曲库。 */
    fun remove(key: String) = update { state ->
        if (state.servers.none { it.key == key }) state
        else Snapshot(state.servers.filterNot { it.key == key },
            state.currentKey.takeUnless { it == key }, state.revision + 1)
    }

    fun setExpired(key: String, expired: Boolean) = update { state ->
        val target = state.servers.firstOrNull { it.key == key } ?: return@update state
        if (target.expired == expired) state
        else state.copy(servers = state.servers.map { if (it.key == key) it.copy(expired = expired) else it },
            revision = state.revision + if (key == state.currentKey) 1 else 0)
    }

    /** 只更新服务器自报的版本与扩展，不改变账号版本号。 */
    fun updateCapabilities(key: String, type: String, version: String, openSubsonic: Boolean, extensions: Set<String>) = update { state ->
        state.copy(servers = state.servers.map {
            if (it.key == key) it.copy(serverType = type, serverVersion = version, openSubsonic = openSubsonic, extensions = extensions) else it
        })
    }

    private fun update(change: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val next = change(snapshot)
            if (next == snapshot) return
            val saved = preferences.edit()
                .putString("servers", encode(next.servers))
                .putString("current", next.currentKey)
                .putLong("revision", next.revision)
                .commit()
            check(saved) { "服务器设置保存失败，请重试" }
            snapshot = next
        }
    }

    private fun read(): Snapshot {
        val servers = runCatching { decode(preferences.getString("servers", null)) }.getOrDefault(emptyList())
        val current = preferences.getString("current", null)?.takeIf { key -> servers.any { it.key == key } }
        return Snapshot(servers, current, preferences.getLong("revision", 0L))
    }

    private fun encode(servers: List<SubsonicServer>): String = JSONArray(servers.map { server ->
        JSONObject().put("key", server.key).put("baseUrl", server.baseUrl).put("username", server.username)
            .put("salt", server.salt).put("token", server.token)
            .put("legacyPasswordHex", server.legacyPasswordHex ?: JSONObject.NULL)
            .put("serverType", server.serverType).put("serverVersion", server.serverVersion)
            .put("openSubsonic", server.openSubsonic).put("extensions", JSONArray(server.extensions.sorted()))
            .put("expired", server.expired).put("addedAtMs", server.addedAtMs)
    }).toString()

    private fun decode(value: String?): List<SubsonicServer> {
        if (value.isNullOrBlank()) return emptyList()
        val array = JSONArray(value)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val baseUrl = item.optString("baseUrl"); val username = item.optString("username")
            if (baseUrl.isBlank() || username.isBlank()) return@mapNotNull null
            val extensions = item.optJSONArray("extensions")
            SubsonicServer(
                key = item.optString("key").ifBlank { subsonicServerKey(baseUrl, username) },
                baseUrl = baseUrl, username = username,
                salt = item.optString("salt"), token = item.optString("token"),
                legacyPasswordHex = if (item.isNull("legacyPasswordHex")) null else item.optString("legacyPasswordHex").ifBlank { null },
                serverType = item.optString("serverType"), serverVersion = item.optString("serverVersion"),
                openSubsonic = item.optBoolean("openSubsonic"),
                extensions = extensions?.let { list -> (0 until list.length()).map(list::optString).filter(String::isNotBlank).toSet() }.orEmpty(),
                expired = item.optBoolean("expired"), addedAtMs = item.optLong("addedAtMs"),
            )
        }
    }
}
