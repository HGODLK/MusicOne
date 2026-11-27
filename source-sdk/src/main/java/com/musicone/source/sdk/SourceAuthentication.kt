package com.musicone.source.sdk

data class SourceAccount(
    val id: String, val displayName: String, val avatarUrl: String? = null,
    val membership: String? = null, val backgroundUrl: String? = null,
    val signature: String = "", val follows: Long? = null, val followers: Long? = null
)
enum class SourceSessionStatus { GUEST, AUTHENTICATED, EXPIRED }
data class SourceAccountState(val status: SourceSessionStatus, val account: SourceAccount?, val revision: Long)

// 完整会话视为不透明数据，不要写入日志，迁移时也不能只搬头像或登录标记。
class SourceSessionSnapshot(val format: String, val version: Int, val fields: Map<String, String>) {
    override fun toString(): String = "SourceSessionSnapshot(format=$format, version=$version, fields=<已隐藏>)"
}
class SourceLegacySession(
    val migrationId: String, val sourceId: String,
    val preferenceNamespaces: Map<String, Map<String, String>>,
    val privateFiles: Map<String, ByteArray> = emptyMap()
) { override fun toString(): String = "SourceLegacySession(migrationId=$migrationId, sourceId=$sourceId, data=<已隐藏>)" }
enum class SourceMigrationStatus { IMPORTED, ALREADY_IMPORTED, EXISTING_SESSION_PRESERVED }
data class SourceMigrationReceipt(val migrationId: String, val status: SourceMigrationStatus)
data class SourceLoginMethod(val id: String, val label: String)
class SourceAuthChallenge(val id: String, val kind: String, val message: String,
    val displayUrl: String? = null, val imageBase64: String? = null, val payload: SourcePayload = SourcePayload()) {
    override fun toString(): String = "SourceAuthChallenge(kind=$kind, content=<已隐藏>)"
}
enum class SourceAuthProgress { WAITING, CONFIRMATION_REQUIRED, VERIFICATION_REQUIRED, SUCCEEDED, EXPIRED }
data class SourceAuthResult(val progress: SourceAuthProgress, val message: String,
    val challenge: SourceAuthChallenge? = null, val account: SourceAccount? = null)
interface SourceAuthentication {
    val methods: List<SourceLoginMethod>
    fun currentAccount(): SourceAccountState
    suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge
    suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult
    suspend fun refreshAccount(): SourceAccountState
    suspend fun logout()
    fun exportSession(): SourceSessionSnapshot?
    // 保存成功后再返回收据；实现需幂等，空输入不能覆盖已有有效会话。
    fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt
}
