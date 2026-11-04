package com.musicone.validation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.MessageDigest

/** 只依赖系统与测试API，可用相同实验包名/签名对0.2执行，不引用新版宿主或SDK。 */
@RunWith(AndroidJUnit4::class)
class UpgradeDataHarnessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val expectedPackage = "io.github.shuyunr.musicone.debugrelease"
    private val controlledRoot: File get() = File(requireNotNull(context.getExternalFilesDir(null)), "upgrade-validation").canonicalFile
    private val dataRoot: File get() = File(context.applicationInfo.dataDir).canonicalFile
    private val fixtureFiles = setOf(
        "FIXTURE_ONLY.json", "databases/musicone_library.db", "shared_prefs/platform_settings.xml",
        "shared_prefs/musicone_bilibili_session.xml", "shared_prefs/musicone_bili_device.xml",
        "shared_prefs/mixed_playback_snapshot.xml", "files/music_library/lyrics/fixture.manual.lrc",
        "files/music_library/lyrics/fixture.default.lrc", "files/music_library/audio/local-fixture.wav",
    )
    private val fakeCredentials = mapOf(
        "session_qq_credential" to "uin=fixture-user; musickey=FIXTURE_NOT_VALID; refresh_token=FIXTURE_REFRESH; refresh_key=FIXTURE_KEY; loginType=2; openid=FIXTURE_OPENID; expired_at=1",
        "session_kugou_credential" to "userid=fixture-user; token=FIXTURE_NOT_VALID; KUGOU_API_MID=FIXTURE_MID; KUGOU_API_GUID=FIXTURE_GUID; KUGOU_API_MAC=FIXTURE_MAC; KUGOU_API_DEV=FIXTURE_DEV; dfid=FIXTURE_DFID",
        "session_netease_credential" to "MUSIC_U=FIXTURE_NOT_VALID; MUSIC_A=FIXTURE_ANONYMOUS; __csrf=FIXTURE_CSRF",
        "cookie" to "SESSDATA=FIXTURE_NOT_VALID; bili_jct=FIXTURE_CSRF; DedeUserID=fixture-bili-user",
    )

    @Test
    fun runOperation() {
        require(context.packageName == expectedPackage) { "只允许指定debugrelease实验包" }
        val operation = requireNotNull(arguments.getString("operation")) { "必须显式指定operation" }
        val output = controlled(requireNotNull(arguments.getString("output")) { "必须显式指定output" })
        require(output.extension == "json") { "只输出脱敏JSON摘要" }
        controlledRoot.mkdirs()
        when (operation) {
            "seed" -> seed(controlled(requireNotNull(arguments.getString("fixtureDir")) { "seed必须显式指定fixtureDir" }))
            "snapshot" -> Unit
            else -> error("operation仅允许seed或snapshot")
        }
        val report = snapshot().put("operation", operation).put("realG8", false)
        output.parentFile?.mkdirs()
        output.writeText(report.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("upgradeDataHarness", "脱敏摘要已写入受控目录；不代表真实G8")
            putString("operation", operation)
        })
    }

    private fun controlled(argument: String): File {
        val supplied = File(argument)
        val file = (if (supplied.isAbsolute) supplied else File(controlledRoot, argument)).canonicalFile
        require(file.path.startsWith(controlledRoot.path + File.separator)) { "路径必须位于实验应用externalFilesDir/upgrade-validation内" }
        return file
    }

    private fun privateFile(relative: String): File {
        val result = File(dataRoot, relative).canonicalFile
        require(result.path.startsWith(dataRoot.path + File.separator)) { "私有路径越界" }
        return result
    }

    private fun seed(fixture: File) {
        val version = context.packageManager.getPackageInfo(expectedPackage, 0).versionName.orEmpty()
        require(version.startsWith("0.2.0")) { "seed只允许迁移前0.2.0实验版本" }
        require(fixture.isDirectory) { "fixtureDir不存在" }
        val manifestFile = File(fixture, "FIXTURE_ONLY.json")
        require(manifestFile.isFile) { "缺少FIXTURE_ONLY.json" }
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.optInt("format") == 1 && manifest.optString("package") == expectedPackage &&
            manifest.has("real_credentials") && !manifest.getBoolean("real_credentials") &&
            manifest.has("real_g8") && !manifest.getBoolean("real_g8")) { "夹具声明不匹配" }
        val actual = fixture.walkTopDown().filter { it.isFile }.map { file ->
            require(file.canonicalPath.startsWith(fixture.canonicalPath + File.separator)) { "夹具路径越界" }
            file.relativeTo(fixture).invariantSeparatorsPath
        }.toSet()
        require(actual == fixtureFiles) { "夹具文件集合与生成器不匹配" }
        val main = prefs(File(fixture, "shared_prefs/platform_settings.xml"))
        fakeCredentials.filterKeys { it != "cookie" }.forEach { (key, value) ->
            require(main[key]?.second == value) { "夹具主会话必须为完整固定假凭证" }
        }
        listOf("session_qq_membership_version", "session_qq_follows", "session_qq_followers",
            "session_kugou_follows", "session_kugou_followers", "session_netease_follows", "session_netease_followers").forEach {
            require(main[it]?.first == "int") { "旧getInt字段必须使用int XML类型" }
        }
        require(prefs(File(fixture, "shared_prefs/musicone_bilibili_session.xml"))["cookie"]?.second == fakeCredentials["cookie"]) { "夹具B站会话必须是假凭证" }
        require(prefs(File(fixture, "shared_prefs/musicone_bili_device.xml"))["buvid3"]?.second == "FIXTURE_BUVID") { "夹具设备身份不匹配" }
        validateFixtureDatabase(File(fixture, "databases/musicone_library.db"))
        refuseRealData()
        // 全部校验成功后才覆盖指定夹具文件，不递归删除任何应用目录。
        context.deleteDatabase("musicone_library.db")
        fixtureFiles.filter { it != "FIXTURE_ONLY.json" }.sorted().forEach { relative ->
            val source = File(fixture, relative)
            val destination = privateFile(relative)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
        privateFile("files/upgrade-validation-fixture-only.json").writeText(manifest.toString())
    }

    private fun validateFixtureDatabase(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version == 2) { "夹具数据库必须为旧版v2" }
            db.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "夹具数据库损坏" } }
            val expected = mapOf("songs" to 6, "playlists" to 3, "members" to 9, "folders" to 1, "locations" to 1)
            expected.forEach { (table, count) -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                require(it.moveToFirst() && it.getInt(0) == count) { "夹具表数量不匹配" }
            } }
            db.rawQuery("SELECT data FROM songs", null).use { rows ->
                while (rows.moveToNext()) {
                    val track = JSONObject(rows.getString(0))
                    require(isFixtureTrack(track.optString("id"))) { "夹具含非固定测试曲目" }
                    require(track.optJSONObject("qualityIds")?.keys()?.asSequence()?.all { it in setOf("STANDARD", "HIGHER", "EXHIGH", "LOSSLESS", "HI_RES", "DOLBY") } != false) { "未知音质枚举名" }
                }
            }
        }
    }

    private fun isFixtureTrack(id: String): Boolean = id in setOf("qq-fixture-qq", "kugou-fixture-kugou", "netease-fixture-netease",
        "bilibili-BV1fixture00_102", "bilibili-BV1fixture00_103", "local-fixture-local")

    private fun refuseRealData() {
        privateFile("shared_prefs").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            prefs(file).forEach { (key, typed) ->
                val value = typed.second
                val sensitive = key.contains("credential", true) || key.contains("cookie", true) ||
                    key.contains("token", true) || key.contains("musickey", true) || key.contains("refresh", true)
                if (sensitive && value.isNotBlank()) require(value in fakeCredentials.values) { "现有非夹具凭证，拒绝seed" }
                if (key == "buvid3" && value.isNotBlank()) {
                    // 新装旧版可能已生成随机BUVID；没有真实会话时允许以夹具替换。
                    require(prefs(privateFile("shared_prefs/musicone_bilibili_session.xml"))["cookie"]?.second.orEmpty().let { it.isBlank() || it == fakeCredentials["cookie"] }) { "真实B站会话存在" }
                }
            }
        }
        val dbFile = privateFile("databases/musicone_library.db")
        if (dbFile.isFile) SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM songs", null).use { rows ->
                while (rows.moveToNext()) require(isFixtureTrack(rows.getString(0))) { "现有非夹具歌曲，拒绝seed" }
            }
        }
        // seed只支持旧版，遇到新包存储不尝试猜测其凭证格式。
        val packData = privateFile("files").walkTopDown().filter { it.isFile && it.extension == "json" && it.name != "upgrade-validation-fixture-only.json" }.toList()
        require(packData.isEmpty()) { "存在其他JSON私有业务数据，拒绝seed覆盖" }
    }

    private fun prefs(file: File): Map<String, Pair<String, String>> {
        if (!file.isFile) return emptyMap()
        val values = linkedMapOf<String, Pair<String, String>>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name != "map") {
                    val tag = parser.name
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null) values[name] = tag to if (tag == "string") parser.nextText() else parser.getAttributeValue(null, "value").orEmpty()
                }
                event = parser.next()
            }
        }
        return values
    }

    private fun snapshot(): JSONObject {
        val report = JSONObject().put("format", 1).put("scope", "data-preservation-only").put("realG8", false)
        val scalarHashes = linkedSetOf<String>()
        val preferenceEvidence = JSONObject()
        privateFile("shared_prefs").listFiles().orEmpty().filter { it.extension == "xml" }.sortedBy { it.name }.forEach { file ->
            val fields = JSONObject()
            prefs(file).forEach { (key, typed) ->
                fields.put(sha(key), evidence(typed.second).put("xmlType", typed.first))
                scalarHashes += sha(typed.second)
                runCatching { collectScalars(JSONObject(typed.second), scalarHashes) }
            }
            preferenceEvidence.put(sha(file.name), fields)
        }
        report.put("preferences", preferenceEvidence)
        val sessions = JSONObject()
        val main = prefs(privateFile("shared_prefs/platform_settings.xml"))
        listOf("qq", "kugou", "netease").forEach { source ->
            sessions.put(source, JSONObject().apply {
                listOf("credential", "device_id", "user_id").forEach { field -> put(field, evidence(main["session_${source}_$field"]?.second.orEmpty())) }
            })
        }
        val bili = prefs(privateFile("shared_prefs/musicone_bilibili_session.xml"))
        sessions.put("bilibili", JSONObject().put("credential", evidence(bili["cookie"]?.second.orEmpty()))
            .put("accountIdentity", evidence(bili["mid"]?.second.orEmpty()))
            .put("deviceId", evidence(prefs(privateFile("shared_prefs/musicone_bili_device.xml"))["buvid3"]?.second.orEmpty())))
        report.put("legacySessionEvidence", sessions)
        report.put("queue", queueEvidence())
        val dbFile = privateFile("databases/musicone_library.db")
        val database = JSONObject()
        if (dbFile.isFile) SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.beginTransactionNonExclusive()
            try {
                db.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "数据库一致性检查失败" } }
                database.put("userVersion", db.version)
                listOf("songs", "playlists", "members", "folders", "locations").forEach { table ->
                    val rows = JSONArray()
                    db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val fields = JSONObject()
                            cursor.columnNames.forEachIndexed { index, column ->
                                val value = if (cursor.isNull(index)) null else cursor.getString(index)
                                if (column == "data" && value != null) {
                                    val track = JSONObject(value)
                                    val semantics = JSONObject()
                                    listOf("id", "title", "artists", "album", "durationMs", "artworkUrl", "localUri").forEach { key -> semantics.put(key, jsonDigest(track.opt(key))) }
                                    semantics.put("source", jsonDigest(track.optString("sourceId", track.optString("source")).lowercase()))
                                    fields.put("trackSemantics", semantics)
                                    val payload = JSONObject()
                                    track.keys().asSequence().sorted().forEach { key -> payload.put(sha(key), jsonDigest(track.opt(key))) }
                                    fields.put("serializedFieldDigests", payload)
                                } else if (column in setOf("lyric_offset", "position", "added_at", "remote_member", "hidden", "manual_order", "copy_files", "size")) {
                                    fields.put(column, value?.toLongOrNull() ?: JSONObject.NULL)
                                } else fields.put(column, if (value == null) JSONObject.NULL else sha(value))
                            }
                            rows.put(fields)
                        }
                    }
                    database.put(table, JSONObject().put("count", rows.length()).put("rows", rows))
                }
            } finally { db.endTransaction() }
        }
        report.put("database", database)
        val assets = JSONObject()
        privateFile("files").walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.canonicalPath.startsWith(dataRoot.path + File.separator)) { "资产路径越界" }
            val relative = file.relativeTo(dataRoot).invariantSeparatorsPath
            assets.put(sha(relative), JSONObject().put("length", file.length()).put("sha256", file.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read >= 0) { if (read > 0) hash.update(buffer, 0, read); read = input.read(buffer) }
                hash.digest().hex()
            }))
            if (file.extension == "json") runCatching { collectScalars(JSONObject(file.readText()), scalarHashes) }
        }
        report.put("assets", assets).put("persistedScalarSha256", JSONArray(scalarHashes.sorted()))
        report.put("note", "旧凭证摘要仍存在不证明新包使用或认证有效；真实G8需独立认证接口证据")
        return report
    }

    private fun queueEvidence(): JSONObject {
        val saved = prefs(privateFile("shared_prefs/mixed_playback_snapshot.xml"))
        val snapshot = saved["snapshot"]?.second?.let { runCatching { JSONObject(it) }.getOrNull() }
        val result = JSONObject().put("present", snapshot != null).put("positionMs", saved["position"]?.second?.toLongOrNull() ?: 0)
        if (snapshot != null) {
            result.put("currentIdentitySha256", sha(snapshot.optString("current")))
            result.put("shuffle", snapshot.optBoolean("shuffle")).put("repeat", snapshot.optString("repeat")).put("radio", snapshot.optBoolean("radio"))
            val queue = snapshot.optJSONArray("queue") ?: JSONArray()
            result.put("count", queue.length()).put("trackIdentitiesSha256", JSONArray((0 until queue.length()).map { sha(queue.optJSONObject(it)?.optString("id").orEmpty()) }))
            val order = snapshot.optJSONArray("order") ?: JSONArray()
            result.put("orderSha256", JSONArray((0 until order.length()).map { sha(order.optString(it)) }))
        }
        return result
    }

    private fun collectScalars(value: Any?, hashes: MutableSet<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> collectScalars(value.opt(key), hashes) }
            is JSONArray -> (0 until value.length()).forEach { collectScalars(value.opt(it), hashes) }
            null, JSONObject.NULL -> Unit
            else -> hashes += sha(value.toString())
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key -> JSONObject.quote(key) + ":" + canonical(value.opt(key)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun jsonDigest(value: Any?): String = sha(canonical(value))
    private fun evidence(value: String): JSONObject = JSONObject().put("present", value.isNotBlank()).put("length", value.length).put("sha256", sha(value))
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
