package com.musicone.source.runtime

import org.json.JSONObject
import com.musicone.source.sdk.SourceKind

internal data class SourceRegistryReadResult(
    val states: MutableMap<String, InstalledSource>,
    val errors: List<String>
)

internal object SourceRegistryReader {
    // 读取失败不能把损坏记录当成首次安装；调用方保留原文件并禁止覆盖。
    fun read(readText: () -> String): SourceRegistryReadResult {
        val json = try { JSONObject(readText()) } catch (_: Exception) {
            return SourceRegistryReadResult(linkedMapOf(), listOf("音源注册表损坏，原文件已保留；本地音源仍可使用"))
        }
        val recovered = linkedMapOf<String, InstalledSource>()
        val errors = mutableListOf<String>()
        json.keys().forEach { id -> try {
            require(id.matches(Regex("[a-z][a-z0-9_.-]{2,127}")))
            val value = json.getJSONObject(id)
            fun version(key: String) = if (value.isNull(key)) null else value.getLong(key).also {
                require(it > 0) { "音源版本编号无效" }
            }
            fun installation(key: String, versionKey: String): String? {
                if (value.isNull(key)) return null
                val installId = value.getString(key)
                SourceInstallRef(requireNotNull(version(versionKey)), installId)
                return installId
            }
            recovered[id] = InstalledSource(id, value.getString("signer"), version("active"), version("pending"),
                version("rollback"), value.optBoolean("removed"), value.optString("error").takeIf { it.isNotEmpty() },
                value.optString("kind").takeIf { it.isNotBlank() }?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() },
                value.optString("name").takeIf { !value.isNull("name") && it.isNotBlank() },
                value.optString("versionName").takeIf { !value.isNull("versionName") && it.isNotBlank() },
                value.optString("pendingVersionName").takeIf { !value.isNull("pendingVersionName") && it.isNotBlank() },
                pendingRemoval = value.optBoolean("pendingRemoval", false),
                activeInstallId = installation("activeInstallId", "active"),
                pendingInstallId = installation("pendingInstallId", "pending"),
                rollbackInstallId = installation("rollbackInstallId", "rollback"))
        } catch (_: Exception) {
            errors.add("音源 $id 安装记录损坏，其他有效音源仍可使用；原文件已保留")
        } }
        return SourceRegistryReadResult(recovered, errors)
    }
}
