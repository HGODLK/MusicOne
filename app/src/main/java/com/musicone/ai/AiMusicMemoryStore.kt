package com.musicone

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

internal data class AiMusicMemorySnapshot(val text: String, val revision: Long)

/** 会话和归档共用同一份简短记忆，导入后旧的后台请求不能覆盖新资料。 */
internal class AiMusicMemoryStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "ai/memory.txt"))
    private var revision = 0L

    @Synchronized fun snapshot(): AiMusicMemorySnapshot {
        val value = if (file.baseFile.isFile || File(file.baseFile.path + ".bak").isFile) {
            file.openRead().bufferedReader().use { it.readText() }
        } else ""
        return AiMusicMemorySnapshot(aiCompactMemory(value), revision)
    }

    @Synchronized fun save(value: String) {
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法保存音乐偏好" }
        val output = file.startWrite()
        try { output.write(aiCompactMemory(value).toByteArray()); file.finishWrite(output); revision++ }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    @Synchronized fun saveIfUnchanged(value: String, expected: AiMusicMemorySnapshot): Boolean {
        if (snapshot() != expected) return false
        save(value)
        return true
    }

    @Synchronized fun export(): JSONObject = JSONObject().put("version", 1).put("preferences", snapshot().text)

    @Synchronized fun restore(data: JSONObject?) {
        if (data == null) return
        require(data.optInt("version") == 1 && data.opt("preferences") is String) { "归档中的音乐偏好格式无效" }
        // 归档导入沿用曲库合并语义，现有偏好优先，旧归档不清除本机记忆。
        save(listOf(snapshot().text, data.getString("preferences")).filter(String::isNotBlank).joinToString("\n"))
    }

    companion object {
        private val stores = java.util.concurrent.ConcurrentHashMap<String, AiMusicMemoryStore>()
        fun get(context: Context): AiMusicMemoryStore = stores.getOrPut(context.applicationContext.filesDir.absolutePath) {
            AiMusicMemoryStore(context)
        }
    }
}

/** 约六百汉字的预算足够保存稳定偏好，不随对话数量增长。 */
internal fun aiCompactMemory(value: String): String {
    val clean = value.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n")
    if (clean.toByteArray(Charsets.UTF_8).size <= 2000) return clean
    var end = minOf(clean.length, 2000)
    while (end > 0 && clean.substring(0, end).toByteArray(Charsets.UTF_8).size > 1997) end--
    if (end > 0 && Character.isHighSurrogate(clean[end - 1])) end--
    return clean.substring(0, end).trimEnd() + "…"
}
