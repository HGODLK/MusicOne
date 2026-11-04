package com.musicone.source.runtime

import com.musicone.source.sdk.SourceDescriptor
import com.musicone.source.sdk.SourceKind

data class InstalledSource(
    val id: String,
    val signerSha256: String,
    val activeVersion: Long?,
    val pendingVersion: Long?,
    val rollbackVersion: Long?,
    val removed: Boolean,
    val error: String?,
    val kind: SourceKind? = null,
    val name: String? = null,
    val versionName: String? = null,
    val pendingVersionName: String? = null,
    val pendingRemoval: Boolean = false,
    val activeInstallId: String? = null,
    val pendingInstallId: String? = null,
    val rollbackInstallId: String? = null,
) {
    internal val activePackage get() = activeVersion?.let { SourceInstallRef(it, activeInstallId ?: it.toString()) }
    internal val pendingPackage get() = pendingVersion?.let { SourceInstallRef(it, pendingInstallId ?: it.toString()) }
    internal val rollbackPackage get() = rollbackVersion?.let { SourceInstallRef(it, rollbackInstallId ?: it.toString()) }

    internal fun activated(candidate: SourceInstallRef, descriptor: SourceDescriptor): InstalledSource {
        val rollback = activePackage?.takeIf { it != candidate } ?: rollbackPackage
        return copy(activeVersion = candidate.versionCode, activeInstallId = candidate.installId,
            pendingVersion = null, pendingInstallId = null, pendingVersionName = null,
            rollbackVersion = rollback?.versionCode, rollbackInstallId = rollback?.installId,
            error = null, kind = descriptor.kind, name = descriptor.name, versionName = descriptor.versionName)
    }

    internal fun withPending(candidate: SourceInstallRef, descriptor: SourceDescriptor): InstalledSource =
        copy(activeVersion = activeVersion.takeUnless { removed }, activeInstallId = activeInstallId.takeUnless { removed },
            rollbackVersion = rollbackVersion.takeUnless { removed }, rollbackInstallId = rollbackInstallId.takeUnless { removed },
            pendingVersion = candidate.versionCode, pendingInstallId = candidate.installId,
            pendingVersionName = descriptor.versionName, kind = descriptor.kind, name = descriptor.name,
            removed = false, error = null)

    internal fun withoutPackages(): InstalledSource = copy(activeVersion = null, pendingVersion = null,
        rollbackVersion = null, activeInstallId = null, pendingInstallId = null, rollbackInstallId = null,
        removed = true, pendingRemoval = false, pendingVersionName = null, error = null)
}

/** 版本号用于展示与升降级判断，安装标识用于文件、激活事务及回退身份。 */
internal data class SourceInstallRef(val versionCode: Long, val installId: String) {
    init {
        require(versionCode > 0 && installId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "音源安装标识无效" }
    }
}
