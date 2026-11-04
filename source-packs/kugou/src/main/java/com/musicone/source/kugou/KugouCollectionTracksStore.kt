package com.musicone.source.kugou

import android.util.AtomicFile
import java.io.File

internal interface KugouCollectionTracksStore {
    fun read(): String?
    fun write(text: String, check: () -> Unit)
    fun delete()
    fun readLines(accept: (String) -> Unit): Boolean {
        val text = read() ?: return false
        text.lineSequence().filter(String::isNotBlank).forEach(accept)
        return true
    }
    fun writeRows(header: String, rows: List<String>, check: () -> Unit) {
        write(buildString {
            appendLine(header)
            rows.forEach { check(); appendLine(it) }
        }, check)
    }
}

/** 原子文件逐行读写完整快照；生产路径不创建整单字符串或整单 UTF-8 字节数组。 */
internal class KugouAtomicTracksStore(private val file: File) : KugouCollectionTracksStore {
    private val atomic = AtomicFile(file)
    private fun exists() = file.exists() || File(file.path + ".bak").exists()
    override fun read(): String? = if (exists()) atomic.openRead().bufferedReader().use { it.readText() } else null
    override fun readLines(accept: (String) -> Unit): Boolean {
        if (!exists()) return false
        atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { if (it.isNotBlank()) accept(it) }
        }
        return true
    }
    override fun write(text: String, check: () -> Unit) = writeRows(text, emptyList(), check)
    override fun writeRows(header: String, rows: List<String>, check: () -> Unit) {
        check()
        file.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.appendLine(header)
            rows.forEach { check(); writer.appendLine(it) }
            writer.flush()
            stream.fd.sync()
            check()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    override fun delete() = atomic.delete()
}
