package com.musicone.source.runtime

import com.musicone.source.sdk.*
import org.json.JSONObject

data class SourceManifest(
    val descriptor: SourceDescriptor,
    val entryClass: String,
    val apiVersion: Int,
    val minAndroid: Int,
    val kotlinVersion: String,
    val composeVersion: String,
    val minHostVersionCode: Long? = null,
    val minHostVersionName: String? = null,
) {
    fun checkCompatibility(androidApi: Int, host: SourceHostCompatibility = SourceHostCompatibility(0, "未知")) {
        require(descriptor.id.matches(Regex("[a-z][a-z0-9_.-]{2,127}"))) { "音源 ID 无效" }
        require(descriptor.versionCode > 0 && descriptor.versionName.isNotBlank()) { "包版本无效" }
        require(apiVersion == SourceSdk.API_VERSION) { "SDK 接口版本不兼容" }
        require(minAndroid in 23..androidApi) { "Android 版本不兼容" }
        host.check(this)
        require(entryClass.contains('.') && entryClass.isNotBlank()) { "包入口无效" }
    }

    companion object {
        fun parse(text: String): SourceManifest {
            val json = JSONObject(text)
            val capabilities = json.getJSONArray("capabilities").let { array ->
                (0 until array.length()).map { SourceCapability.valueOf(array.getString(it)) }.toSet()
            }
            val permissions = json.optJSONObject("permissions") ?: JSONObject()
            val presentation = json.optJSONObject("presentation") ?: JSONObject()
            return SourceManifest(
                SourceDescriptor(
                    id = json.getString("id"), name = json.getString("name"),
                    kind = SourceKind.valueOf(json.getString("kind")),
                    versionCode = json.getLong("versionCode"), versionName = json.getString("versionName"),
                    capabilities = capabilities,
                    useCustomPages = json.optBoolean("useCustomPages", false),
                    requiresLogin = json.optBoolean("requiresLogin", false),
                    permissions = SourcePermissions(permissions.optBoolean("editTrackMetadata"),
                        permissions.optBoolean("replaceManualLyrics"), permissions.optBoolean("adjustLineOffset")),
                    presentation = SourcePresentation(
                        if (presentation.has("pageColorArgb")) presentation.getLong("pageColorArgb") else null,
                        if (presentation.has("radioColorArgb")) presentation.getLong("radioColorArgb") else null,
                        presentation.optString("radioLabel", "猜你喜欢"),
                        presentation.optLong("lineLeadMs"), presentation.optLong("wordLeadMs"),
                        presentation.optJSONArray("radioPaletteLight")?.let { colors ->
                            (0 until colors.length()).map { colors.getLong(it) }
                        }.orEmpty(),
                        presentation.optJSONArray("radioPaletteDark")?.let { colors ->
                            (0 until colors.length()).map { colors.getLong(it) }
                        }.orEmpty()
                    )
                ),
                json.getString("entryClass"), json.getInt("apiVersion"), json.getInt("minAndroid"),
                json.getString("kotlinVersion"), json.getString("composeVersion"),
                if (json.has("minHostVersionCode")) {
                    val value = json.get("minHostVersionCode")
                    require(value is Int || value is Long) { "最低宿主版本编号必须是正整数" }
                    (value as Number).toLong()
                } else null,
                if (json.has("minHostVersionName")) {
                    val value = json.get("minHostVersionName")
                    require(value is String && value.isNotBlank() && json.has("minHostVersionCode")) {
                        "最低宿主展示版本必须与版本编号一起声明"
                    }
                    value
                } else null,
            )
        }
    }
}

data class PackageIdentity(val sourceId: String, val signerSha256: String, val versionCode: Long)
enum class ImportDecision { INSTALL, UPDATE_ON_RESTART, ALREADY_INSTALLED }

object SourceImportPolicy {
    fun decide(previous: PackageIdentity?, incoming: PackageIdentity, allowDowngrade: Boolean = true,
        sameContent: Boolean = false): ImportDecision {
        if (previous == null) return ImportDecision.INSTALL
        require(previous.sourceId == incoming.sourceId) { "音源 ID 不匹配" }
        require(previous.signerSha256 == incoming.signerSha256) { "相同音源 ID 的签名身份不一致" }
        if (previous.versionCode == incoming.versionCode && sameContent) return ImportDecision.ALREADY_INSTALLED
        require(incoming.versionCode >= previous.versionCode || allowDowngrade) { "当前安装入口不允许降级" }
        return ImportDecision.UPDATE_ON_RESTART
    }
}
