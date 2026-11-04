package com.musicone.source.runtime

import java.io.File

internal fun InstalledSource.scheduledForRemoval(): InstalledSource {
    check(!removed) { "音源已经删除" }
    return copy(pendingRemoval = true, error = null)
}

internal fun InstalledSource.removalUndone(): InstalledSource {
    check(pendingRemoval && !removed) { "音源没有待删除操作" }
    return copy(pendingRemoval = false, error = null)
}

/** 仅清理包私有文件，保留宿主曲目、资料库、队列及离线缓存。 */
internal fun completeSourceRemoval(root: File, state: InstalledSource): InstalledSource {
    check(state.pendingRemoval || (state.removed && state.kind == com.musicone.source.sdk.SourceKind.MAIN)) {
        "音源没有待删除操作"
    }
    require(state.id.matches(Regex("[a-z][a-z0-9_.-]{2,127}")))
    val boundary = root.canonicalFile
    fun deleteScoped(file: File) {
        val resolved = file.canonicalFile
        check(resolved.path.startsWith(boundary.path + File.separator) && resolved == file.absoluteFile) {
            "音源删除路径超出私有目录"
        }
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteScoped) ?: error("无法读取音源私有目录")
        }
        check(file.delete() || !file.exists()) { "无法删除音源私有文件" }
    }
    listOf("packages/${state.id}", "data/${state.id}", "data/${state.id}-cache",
        "activation-backup/${state.id}", "activation-backup/${state.id}.staging")
        .forEach { deleteScoped(File(boundary, it)) }
    return state.withoutPackages()
}
