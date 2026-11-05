package com.musicone

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 使用独立资料库和真实 msco 文件验证记忆备份，不读取或覆盖用户资料。 */
@RunWith(AndroidJUnit4::class)
class AiMemoryArchiveTest {
    @Test fun mscoExportPreviewAndMergeRestoreMemoryWithLibrary() = isolated { source, target ->
        source.memory.save("喜欢爵士音乐\n跑步偏好轻快节奏")
        val song = MusicTrack("archive-memory-song", MusicSource.LOCAL, "归档歌曲", "歌手", "专辑", 1000, 0, 0, "", "")
        val playlist = source.store.createPlaylist("记忆归档歌单")
        source.store.addTracks(playlist, listOf(song))
        val exported = source.exportFile()
        val preview = target.archive.preview(Uri.fromFile(exported))
        assertEquals("msco", JSONObject(readZipEntry(exported, "manifest.json")).getString("format"))
        assertEquals(source.memory.snapshot().text, preview.data.getJSONObject("aiMemory").getString("preferences"))
        assertEquals(1, preview.songs)
        assertFalse(preview.containsAudio)
        assertEquals("", target.memory.snapshot().text)
        target.archive.merge(preview)
        assertEquals(source.memory.snapshot().text, target.memory.snapshot().text)
        assertEquals(listOf(song.id), target.store.state.value.playlists.first { it.id == playlist }.tracks.map { it.id })
        assertFalse(preview.folder.exists())
        val constructor = AiMusicMemoryStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        assertEquals(target.memory.snapshot().text, constructor.newInstance(target.context).snapshot().text)
    }

    @Test fun legacyArchiveWithoutMemoryDoesNotClearOrReviseExistingMemory() = isolated { source, target ->
        source.memory.save("旧设备偏好")
        val exported = source.exportFile()
        val legacy = File(source.context.cacheDir, "legacy.msco")
        rewriteLibrary(exported, legacy) { it.remove("aiMemory") }
        target.memory.save("本机现有音乐偏好")
        val before = target.memory.snapshot()
        val preview = target.archive.preview(Uri.fromFile(legacy))
        assertFalse(preview.data.has("aiMemory"))
        target.archive.merge(preview)
        assertEquals(before, target.memory.snapshot())
    }

    @Test fun memoryPersistsDeduplicatedWithinTwoThousandUtf8BytesAcrossArchive() = isolated { source, target ->
        source.memory.save("  喜欢爵士  \n喜欢爵士\n\n  轻快节奏  ")
        assertEquals("喜欢爵士\n轻快节奏", source.memory.snapshot().text)
        val longPreference = "喜欢爵士\n喜欢爵士\n" + "偏好中文与🎵".repeat(600)
        source.memory.save(longPreference)
        val saved = source.memory.snapshot().text
        assertTrue(saved.toByteArray(Charsets.UTF_8).size <= 2000)
        assertEquals(1, saved.lineSequence().count { it == "喜欢爵士" })
        assertFalse(saved.contains('\uFFFD'))
        assertEquals(saved, String(saved.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val preview = target.archive.preview(Uri.fromFile(source.exportFile()))
        assertTrue(preview.data.getJSONObject("aiMemory").getString("preferences").toByteArray(Charsets.UTF_8).size <= 2000)
        target.archive.merge(preview)
        assertEquals(saved, target.memory.snapshot().text)
        target.memory.restore(target.memory.export())
        assertEquals(saved, target.memory.snapshot().text)
    }

    @Test fun importedMemoryMergesWithLocalPreferencesAndRejectsOldBackgroundSnapshot() = isolated { source, target ->
        source.memory.save("喜欢爵士\n喜欢钢琴")
        target.memory.save("喜欢摇滚\n喜欢爵士")
        val expectedByOldRequest = target.memory.snapshot()
        val preview = target.archive.preview(Uri.fromFile(source.exportFile()))
        target.archive.merge(preview)
        assertEquals("喜欢摇滚\n喜欢爵士\n喜欢钢琴", target.memory.snapshot().text)
        assertFalse(target.memory.saveIfUnchanged("旧请求不能覆盖导入结果", expectedByOldRequest))
        assertEquals("喜欢摇滚\n喜欢爵士\n喜欢钢琴", target.memory.snapshot().text)
    }

    @Test fun importingIdenticalTextStillInvalidatesOldBackgroundSnapshot() = isolated { source, target ->
        source.memory.save("喜欢爵士")
        target.memory.save("喜欢爵士")
        val expectedByOldRequest = target.memory.snapshot()
        target.archive.merge(target.archive.preview(Uri.fromFile(source.exportFile())))
        assertEquals(expectedByOldRequest.text, target.memory.snapshot().text)
        assertTrue(target.memory.snapshot().revision > expectedByOldRequest.revision)
        assertFalse(target.memory.saveIfUnchanged("旧后台结果", expectedByOldRequest))
        val fresh = target.memory.snapshot()
        assertTrue(target.memory.saveIfUnchanged("新请求更新", fresh))
        assertEquals("新请求更新", target.memory.snapshot().text)
    }

    private data class Fixture(val context: Context, val store: MusicLibraryStore) {
        val memory get() = AiMusicMemoryStore.get(context)
        val archive get() = MusicLibraryArchive(context, store)
        suspend fun exportFile(): File = File(context.cacheDir, "memory-${UUID.randomUUID()}.msco").also {
            archive.export(Uri.fromFile(it), includeAudio = false)
            check(it.length() > 0)
        }
    }

    private fun readZipEntry(file: File, name: String): String = ZipInputStream(file.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == name) return@use zip.readBytes().toString(Charsets.UTF_8)
            zip.closeEntry()
            entry = zip.nextEntry
        }
        error("归档中没有 $name")
    }

    private fun rewriteLibrary(input: File, output: File, edit: (JSONObject) -> Unit) {
        ZipInputStream(input.inputStream()).use { source ->
            ZipOutputStream(output.outputStream()).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val data = source.readBytes()
                    target.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "library.json") {
                        val library = JSONObject(data.toString(Charsets.UTF_8))
                        edit(library)
                        target.write(library.toString().toByteArray(Charsets.UTF_8))
                    } else target.write(data)
                    target.closeEntry()
                    source.closeEntry()
                }
            }
        }
    }

    private fun isolated(block: suspend (Fixture, Fixture) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "ai-memory-archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        val fixtures = listOf("source", "target").map { name ->
            val folder = File(root, name).apply { mkdirs() }
            val files = File(folder, "files").apply { mkdirs() }
            val cache = File(folder, "cache").apply { mkdirs() }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
                override fun getCacheDir(): File = cache
                override fun getDatabasePath(name: String): File = File(folder, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                    SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            }
            val constructor = MusicLibraryStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            Fixture(context, constructor.newInstance(context)).also { it.store.refresh() }
        }
        try { block(fixtures[0], fixtures[1]) } finally {
            fixtures.forEach { it.store.database().close() }
            root.deleteRecursively()
        }
    }
}
