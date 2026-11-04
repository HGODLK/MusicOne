package com.musicone.source.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** 每个音源只组合宿主与自身的资源表，不向宿主全局 Resources 挂入外部 APK。 */
internal object SourceResources {
    fun load(host: Context, archive: File): Resources {
        val ownIds = packageIds(archive)
        require(ownIds.all { it in 0x02..0x7e }) {
            "音源资源 packageId 必须独立于系统 0x01 和宿主 0x7f；请按 SDK 示例配置重新构建音源包"
        }
        val hostInfo = host.applicationInfo
        val hostIds = (listOf(hostInfo.sourceDir) + hostInfo.splitSourceDirs.orEmpty()).flatMap { packageIds(File(it)) }.toSet()
        require(ownIds.intersect(hostIds).isEmpty()) { "音源资源 packageId 与宿主资源冲突，请重新分配后构建" }
        val resourceInfo = ApplicationInfo(hostInfo).apply {
            // 此资源集合以宿主为 base，保留共享 AndroidX R 的 0x7f 引用；音源表使用独立 ID。
            // splitSourceDirs/splitPublicSourceDirs 自 API 21 提供，API 23 无须隐藏接口或 ResourcesLoader。
            splitSourceDirs = (hostInfo.splitSourceDirs.orEmpty().toList() + archive.absolutePath).toTypedArray()
            splitPublicSourceDirs = (hostInfo.splitPublicSourceDirs.orEmpty().toList() + archive.absolutePath).toTypedArray()
        }
        return host.packageManager.getResourcesForApplication(resourceInfo)
    }

    /** 只读取资源表的包头；没有私有 Android 资源的代码包可以没有 resources.arsc。 */
    private fun packageIds(apk: File): Set<Int> = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("resources.arsc") ?: return@use emptySet()
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val table = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && (table.getShort(0).toInt() and 0xffff) == 0x0002) { "Android 资源表头无效" }
        val tableSize = table.getInt(4)
        var offset = table.getShort(2).toInt() and 0xffff
        require(offset >= 12 && tableSize in offset..bytes.size) { "Android 资源表大小无效" }
        val ids = linkedSetOf<Int>()
        while (offset < tableSize) {
            require(tableSize - offset >= 8) { "Android 资源块头不完整" }
            val type = table.getShort(offset).toInt() and 0xffff
            val headerSize = table.getShort(offset + 2).toInt() and 0xffff
            val size = table.getInt(offset + 4)
            require(headerSize >= 8 && size >= headerSize && size <= tableSize - offset) { "Android 资源块大小无效" }
            if (type == 0x0200) {
                require(headerSize >= 12) { "Android 资源包头不完整" }
                ids += table.getInt(offset + 8)
            }
            offset += size
        }
        ids
    }
}
