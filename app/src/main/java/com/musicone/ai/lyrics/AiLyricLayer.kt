package com.musicone

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * AI 歌词层：AI 写的歌词文件和 AI 加的翻译、注音都存在独立位置，原歌词文件、在线缓存和手动关联都不改。
 * 每首歌一行记录：
 * - file：AI 歌词文件（ai_lyrics 目录下的文件名、来源、时间是否为估算），存在时替代原歌词显示；
 * - translation / ruby：按行号叠加的翻译和注音，记录编写时底层歌词的指纹，底层歌词变了就自动失效。
 */
internal object AiLyricLayer {
    const val TABLE = "ai_lyric_layers"
    const val DIRECTORY = "ai_lyrics"
    val annotationKinds = listOf("translation", "ruby")

    private val changes = MutableStateFlow(0L)
    /** 每次确认或撤销歌词修改后递增，播放器据此重新装载当前歌词。 */
    val revision: StateFlow<Long> = changes.asStateFlow()
    @Volatile private var schemaReady = false

    fun key(track: MusicTrack) = "${track.source.id}|${track.id}"

    fun directory(store: MusicLibraryStore) = File(store.assets, DIRECTORY)

    fun ensureSchema(db: SQLiteDatabase) {
        if (schemaReady) return
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE(track_key TEXT PRIMARY KEY, data TEXT NOT NULL)")
        schemaReady = true
    }

    /** 原样返回存储的字符串，确认时逐字比较，任何中途变化都会让旧计划失效。 */
    fun raw(db: SQLiteDatabase, key: String): String? {
        ensureSchema(db)
        return db.rawQuery("SELECT data FROM $TABLE WHERE track_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun write(db: SQLiteDatabase, key: String, data: String?) {
        ensureSchema(db)
        if (data == null || JSONObject(data).length() == 0) db.delete(TABLE, "track_key=?", arrayOf(key))
        else db.execSQL("INSERT OR REPLACE INTO $TABLE(track_key,data) VALUES(?,?)", arrayOf(key, data))
    }

    fun load(store: MusicLibraryStore, track: MusicTrack): JSONObject? =
        runCatching { raw(store.database(), key(track))?.let(::JSONObject) }.getOrNull()

    /** 底层歌词：有 AI 歌词文件时用它，否则走原有歌词链路。 */
    suspend fun base(store: MusicLibraryStore, track: MusicTrack, layer: JSONObject?,
        original: suspend () -> List<TimedLyric>): List<TimedLyric> {
        val file = layer?.optJSONObject("file")?.let { aiFile(store, track, it) }
        return file ?: original()
    }

    /** 播放器、缓存和预载统一从这里取歌词，AI 层不存在时与原链路完全一致。 */
    suspend fun compose(store: MusicLibraryStore, track: MusicTrack, original: suspend () -> List<TimedLyric>): List<TimedLyric> {
        val layer = withContext(Dispatchers.IO) { load(store, track) } ?: return original()
        return annotate(base(store, track, layer, original), layer)
    }

    private suspend fun aiFile(store: MusicLibraryStore, track: MusicTrack, file: JSONObject): List<TimedLyric>? = withContext(Dispatchers.IO) {
        val target = File(directory(store), file.optString("name")).takeIf { it.isFile } ?: return@withContext null
        val lines = parseLibraryLyrics(target.readText()).takeIf { it.isNotEmpty() } ?: return@withContext null
        if (!track.source.supportsManualLyrics) return@withContext lines
        // 与手动歌词一致：资料库时间线在装载时应用一次用户偏移。
        val offset = store.lyricAsset(track.id).second
        lines.map { line -> line.copy(timeMs = line.timeMs + offset, libraryTimeline = true,
            words = line.words.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }) }
    }

    /** 指纹只看每行原文，翻译、注音或时间偏移变化不影响叠加层。 */
    fun fingerprint(lines: List<TimedLyric>): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(lines.joinToString("\n") { it.text }.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun annotate(lines: List<TimedLyric>, layer: JSONObject): List<TimedLyric> {
        if (lines.isEmpty()) return lines
        val fingerprint = fingerprint(lines)
        val translation = layer.optJSONObject("translation")?.takeIf { it.optString("fingerprint") == fingerprint }
        val ruby = layer.optJSONObject("ruby")?.takeIf { it.optString("fingerprint") == fingerprint }
        if (translation == null && ruby == null) return lines
        return lines.mapIndexed { index, line ->
            var result = line
            translation?.optJSONObject("lines")?.optString(index.toString())?.takeIf { it.isNotBlank() }?.let { text ->
                if (translation.optBoolean("replace") || line.translation.isNullOrBlank()) result = result.copy(translation = text)
            }
            ruby?.optJSONObject("lines")?.optString(index.toString())?.takeIf { lyricRubyAnchors(line.text, it) != null }?.let { text ->
                if (ruby.optBoolean("replace") || line.transliteration.isNullOrBlank()) result = result.copy(transliteration = text)
            }
            result
        }
    }

    /** 提交成功后通知刷新，并清理不再被任何记录引用的 AI 歌词文件；撤销所需内容保存在计划里。 */
    fun committed(store: MusicLibraryStore) {
        runCatching {
            val db = store.database()
            ensureSchema(db)
            val referenced = db.rawQuery("SELECT data FROM $TABLE", null).use { rows ->
                buildSet { while (rows.moveToNext()) JSONObject(rows.getString(0)).optJSONObject("file")?.optString("name")?.let(::add) }
            }
            directory(store).listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach(File::delete)
        }
        changes.value++
    }
}
