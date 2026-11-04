package com.musicone.source.runtime

import java.io.File
import java.util.UUID

internal class SourcePackageStore(private val root: File) {
    fun file(sourceId: String, reference: SourceInstallRef): File =
        directory(sourceId).listFiles()?.singleOrNull { it.nameWithoutExtension == reference.installId }
            ?: error("音源安装文件缺失")

    fun sameContent(sourceId: String, reference: SourceInstallRef, incoming: File): Boolean =
        runCatching { sameSourcePackageContent(incoming, file(sourceId, reference)) }.getOrDefault(false)

    fun retain(sourceId: String, version: Long, extension: String, incoming: File, old: InstalledSource?): SourceInstallRef {
        // 仅复用已有的同内容安装，不覆盖仍在运行或承担回退职责的文件。
        listOfNotNull(old?.pendingPackage, old?.activePackage, old?.rollbackPackage)
            .firstOrNull { it.versionCode == version && sameContent(sourceId, it, incoming) }?.let { return it }
        val reference = SourceInstallRef(version, UUID.randomUUID().toString())
        val destination = File(directory(sourceId), "${reference.installId}.$extension")
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        check(!destination.exists() && incoming.renameTo(destination)) { "无法保存已验证的音源包" }
        return reference
    }

    fun cleanup(state: InstalledSource) {
        val keep = listOfNotNull(state.activePackage, state.pendingPackage, state.rollbackPackage).map { it.installId }.toSet()
        directory(state.id).listFiles()?.filter { it.nameWithoutExtension !in keep }?.forEach { it.delete() }
    }

    private fun directory(sourceId: String): File {
        require(sourceId.matches(Regex("[a-z][a-z0-9_.-]{2,127}"))) { "音源 ID 无效" }
        return File(root, "packages/$sourceId")
    }
}
