package com.musicone.validation

import android.content.Context
import android.os.Bundle
import com.musicone.source.sdk.SourceEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

/** 优化宿主的测试入口仅由该 APK 的 mapping 参数指定，不约定宿主私有二进制名称。 */
internal object OptimizedSmokeEntryAccess {
    suspend fun readWhenConfigured(context: Context, arguments: Bundle, ids: List<String>): Map<String, SourceEntry>? {
        val className = arguments.getString("smokeControllerClass") ?: return null
        fun required(name: String): String = requireNotNull(arguments.getString(name)) { "缺少参数 $name" }
            .also { require(it.isNotBlank()) { "参数 $name 不能为空" } }
        val expectedHash = required("smokeHostSha256")
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actualHash.equals(expectedHash, ignoreCase = true)) { "测试 mapping 参数与安装的宿主 APK 不匹配" }
        val controllerClass = Class.forName(className, false, context.classLoader)
        val singleton = controllerClass.getDeclaredField(required("smokeSingletonField")).apply { isAccessible = true }
        val repositoryName = required("smokeRepositoryField")
        val liveName = required("smokeLiveField")
        val entryName = required("smokeEntryField")
        return withTimeout(60_000L) {
            while (true) {
                val controller = singleton.get(null)
                if (controller != null) {
                    val repository = controllerClass.getDeclaredField(repositoryName).apply { isAccessible = true }.get(controller)
                    val live = requireNotNull(repository).javaClass.getDeclaredField(liveName).apply { isAccessible = true }
                        .get(repository) as Map<*, *>
                    val entries = ids.mapNotNull { id ->
                        live[id]?.let { instance ->
                            val entry = instance.javaClass.getDeclaredField(entryName).apply { isAccessible = true }.get(instance) as SourceEntry
                            check(entry.descriptor.id == id)
                            id to entry
                        }
                    }.toMap()
                    if (entries.size == ids.size) return@withTimeout entries
                }
                delay(200L)
            }
            @Suppress("UNREACHABLE_CODE") error("未完成初始化")
        }
    }
}
