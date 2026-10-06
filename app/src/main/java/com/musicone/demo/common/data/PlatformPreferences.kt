package com.musicone.demo

import android.content.Context
import androidx.core.content.edit

internal data class PlatformSession(
    val source: MusicSource,
    val credential: String,
    val deviceId: String,
    val account: MusicAccount?,
)

internal data class SavedPlatformSettings(
    val selectedSource: MusicSource,
    val sourceSelected: Boolean,
    val sessions: Map<MusicSource, PlatformSession>,
) {
    fun session(source: MusicSource): PlatformSession = sessions[source] ?: PlatformSession(
        source = source,
        credential = "",
        deviceId = "",
        account = null,
    )
}

internal class PlatformPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)

    fun read(): SavedPlatformSettings {
        migrateLegacyNeteaseSession()
        val source = runCatching {
            MusicSource.valueOf(preferences.getString(KEY_SOURCE, MusicSource.NETEASE.name).orEmpty())
        }.getOrDefault(MusicSource.NETEASE)
        return SavedPlatformSettings(
            selectedSource = source,
            sourceSelected = preferences.contains(KEY_SOURCE),
            sessions = MusicSource.entries.associateWith(::readSession),
        )
    }

    fun readSession(source: MusicSource): PlatformSession {
        val credential = preferences.getString(key(source, FIELD_CREDENTIAL), "").orEmpty()
        val deviceId = preferences.getString(key(source, FIELD_DEVICE_ID), "").orEmpty().ifBlank {
            NeteaseCrypto.randomDeviceId().also { generated ->
                preferences.edit { putString(key(source, FIELD_DEVICE_ID), generated) }
            }
        }
        val userId = preferences.getString(key(source, FIELD_USER_ID), "").orEmpty()
        val account = if (credential.isNotBlank() && userId.isNotBlank()) {
            MusicAccount(
                source = source,
                userId = userId,
                nickname = preferences.getString(key(source, FIELD_NICKNAME), "").orEmpty(),
                avatarUrl = preferences.getString(key(source, FIELD_AVATAR_URL), null)?.asHttpsUrl(),
                hasVipAccess = preferences.getBoolean(key(source, FIELD_VIP_ACCESS), false) &&
                    (source != MusicSource.QQ || preferences.getInt(key(source, "membership_version"), 0) == 1),
                backgroundUrl = preferences.getString(key(source, FIELD_BACKGROUND_URL), null)?.asHttpsUrl(),
                signature = preferences.getString(key(source, FIELD_SIGNATURE), "").orEmpty(),
                follows = preferences.getInt(key(source, FIELD_FOLLOWS), 0),
                followers = preferences.getInt(key(source, FIELD_FOLLOWERS), 0),
            )
        } else {
            null
        }
        if (source == MusicSource.QQ) QqEntitlements.activate(credential)
        return PlatformSession(source, credential, deviceId, account)
    }

    fun credential(source: MusicSource): String = readSession(source).credential

    fun saveSource(source: MusicSource) {
        preferences.edit { putString(KEY_SOURCE, source.name) }
    }

    fun saveSession(credential: String, account: MusicAccount) {
        val previous = preferences.getString(key(account.source, FIELD_CREDENTIAL), "").orEmpty()
        if (account.source == MusicSource.QQ && previous != credential) QqEntitlements.forget(previous)
        preferences.edit {
            putString(KEY_SOURCE, account.source.name)
            putString(key(account.source, FIELD_CREDENTIAL), credential)
            putString(key(account.source, FIELD_USER_ID), account.userId)
            putString(key(account.source, FIELD_NICKNAME), account.nickname)
            putString(key(account.source, FIELD_AVATAR_URL), account.avatarUrl)
            putBoolean(key(account.source, FIELD_VIP_ACCESS), account.hasVipAccess)
            if (account.source == MusicSource.QQ) putInt(key(account.source, "membership_version"), 1)
            putString(key(account.source, FIELD_BACKGROUND_URL), account.backgroundUrl)
            putString(key(account.source, FIELD_SIGNATURE), account.signature)
            putInt(key(account.source, FIELD_FOLLOWS), account.follows)
            putInt(key(account.source, FIELD_FOLLOWERS), account.followers)
        }
        if (account.source == MusicSource.QQ) QqEntitlements.activate(credential)
    }

    fun clearSession(source: MusicSource) {
        if (source == MusicSource.QQ) {
            QqEntitlements.forget(preferences.getString(key(source, FIELD_CREDENTIAL), "").orEmpty())
        }
        preferences.edit {
            remove(key(source, FIELD_CREDENTIAL))
            remove(key(source, FIELD_USER_ID))
            remove(key(source, FIELD_NICKNAME))
            remove(key(source, FIELD_AVATAR_URL))
            remove(key(source, FIELD_VIP_ACCESS))
            remove(key(source, FIELD_BACKGROUND_URL))
            remove(key(source, FIELD_SIGNATURE))
            remove(key(source, FIELD_FOLLOWS))
            remove(key(source, FIELD_FOLLOWERS))
        }
    }

    private fun migrateLegacyNeteaseSession() {
        if (preferences.contains(key(MusicSource.NETEASE, FIELD_CREDENTIAL))) return
        val cookie = preferences.getString(KEY_LEGACY_COOKIE, "").orEmpty()
        val userId = preferences.getString(KEY_LEGACY_USER_ID, "").orEmpty()
        val deviceId = preferences.getString(KEY_LEGACY_DEVICE_ID, "").orEmpty()
        if (cookie.isBlank() && userId.isBlank() && deviceId.isBlank()) return
        preferences.edit {
            if (cookie.isNotBlank()) putString(key(MusicSource.NETEASE, FIELD_CREDENTIAL), cookie)
            if (userId.isNotBlank()) putString(key(MusicSource.NETEASE, FIELD_USER_ID), userId)
            if (deviceId.isNotBlank()) putString(key(MusicSource.NETEASE, FIELD_DEVICE_ID), deviceId)
            preferences.getString(KEY_LEGACY_NICKNAME, null)?.let {
                putString(key(MusicSource.NETEASE, FIELD_NICKNAME), it)
            }
            preferences.getString(KEY_LEGACY_AVATAR_URL, null)?.let {
                putString(key(MusicSource.NETEASE, FIELD_AVATAR_URL), it)
            }
            remove(KEY_LEGACY_COOKIE)
            remove(KEY_LEGACY_DEVICE_ID)
            remove(KEY_LEGACY_USER_ID)
            remove(KEY_LEGACY_NICKNAME)
            remove(KEY_LEGACY_AVATAR_URL)
        }
    }

    private fun key(source: MusicSource, field: String): String = "session_${source.name.lowercase()}_$field"

    companion object {
        private const val KEY_SOURCE = "selected_source"
        private const val FIELD_CREDENTIAL = "credential"
        private const val FIELD_DEVICE_ID = "device_id"
        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_NICKNAME = "nickname"
        private const val FIELD_AVATAR_URL = "avatar_url"
        private const val FIELD_VIP_ACCESS = "vip_access"
        private const val FIELD_BACKGROUND_URL = "background_url"
        private const val FIELD_SIGNATURE = "signature"
        private const val FIELD_FOLLOWS = "follows"
        private const val FIELD_FOLLOWERS = "followers"

        private const val KEY_LEGACY_COOKIE = "netease_cookie"
        private const val KEY_LEGACY_DEVICE_ID = "netease_device_id"
        private const val KEY_LEGACY_USER_ID = "user_id"
        private const val KEY_LEGACY_NICKNAME = "nickname"
        private const val KEY_LEGACY_AVATAR_URL = "avatar_url"
    }
}

private fun String.asHttpsUrl(): String = replaceFirst("http://", "https://")
