package com.musicone.source.runtime

import java.io.File

// 完成备份以目录原子改名发布；不完整 staging 永远不能覆盖仍有效的数据。
internal class ActivationTransaction(private val directory: File, private val data: File) {
    private val staging = File(directory.parentFile, directory.name + ".staging")
    fun recover(activeVersion: Long?, pendingVersion: Long?, activeInstallId: String? = null,
        pendingInstallId: String? = null) {
        if (!directory.exists()) { staging.deleteRecursively(); return }
        val target = File(directory, "target").readText().toLong()
        val targetId = File(directory, "target-install").takeIf { it.exists() }?.readText() ?: target.toString()
        if (activeVersion == target && (activeInstallId ?: activeVersion.toString()) == targetId &&
            pendingVersion == null && pendingInstallId == null) { finish(); return }
        restore()
        finish()
    }
    fun begin(target: Long, installId: String = target.toString()) {
        check(!directory.exists()) { "尚有未恢复的初始化事务" }
        staging.deleteRecursively()
        staging.mkdirs()
        val snapshot = File(staging, "data").apply { mkdirs() }
        if (data.exists()) check(data.copyRecursively(snapshot, overwrite = true))
        File(staging, "target").writeText(target.toString())
        File(staging, "target-install").writeText(installId)
        directory.parentFile!!.mkdirs()
        check(staging.renameTo(directory)) { "无法发布完整数据备份" }
    }
    fun restore() {
        if (!directory.exists()) return
        val snapshot = File(directory, "data")
        check(snapshot.isDirectory && File(directory, "target").isFile) { "初始化备份不完整" }
        val replacement = File(directory, "restored")
        replacement.deleteRecursively()
        replacement.mkdirs()
        check(snapshot.copyRecursively(replacement, overwrite = true))
        val displaced = File(directory, "displaced")
        displaced.deleteRecursively()
        if (data.exists()) check(data.renameTo(displaced)) { "无法暂存初始化失败的数据" }
        data.parentFile!!.mkdirs()
        check(replacement.renameTo(data)) { "无法恢复音源数据" }
        displaced.deleteRecursively()
    }
    fun finish() { directory.deleteRecursively(); staging.deleteRecursively() }
}
