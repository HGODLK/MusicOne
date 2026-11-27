package com.musicone.source.runtime

/** 随主程序自动升级同签名预装包，保留用户的删除记录、待生效切换和更高的已装版本。 */
internal fun shouldInstallBundledSource(installed: InstalledSource?, signer: String, version: Long): Boolean =
    installed == null || (!installed.removed && !installed.pendingRemoval && installed.pendingVersion == null &&
        installed.signerSha256 == signer && version > maxOf(installed.activeVersion ?: -1, installed.rollbackVersion ?: -1))
