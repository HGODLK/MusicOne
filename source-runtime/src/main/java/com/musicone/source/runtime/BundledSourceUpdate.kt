package com.musicone.source.runtime

/** 随主程序升级同签名预装包；尊重删除、用户待生效选择以及更高的已安装版本。 */
internal fun shouldInstallBundledSource(installed: InstalledSource?, signer: String, version: Long): Boolean =
    installed == null || (!installed.removed && !installed.pendingRemoval && installed.pendingVersion == null &&
        installed.signerSha256 == signer && version > maxOf(installed.activeVersion ?: -1, installed.rollbackVersion ?: -1))
