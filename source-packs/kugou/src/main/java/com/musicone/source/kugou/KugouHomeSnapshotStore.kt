package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 包私有成功快照，不保存凭据；按账号隔离，每日推荐按酷狗所在自然日更新。 */
internal class KugouHomeSnapshotStore(private val directory: File, private val today: () -> String = ::kugouHomeDayKey) {
    private val publication = Any()
    fun dayKey(): String = today()
    fun read(account: String?): KugouHomeState? = runCatching {
        val target = file(account)
        val encoded = synchronized(publication) { (target.takeIf(File::exists) ?: File("${target.path}.bak")).readText() }
        val root = JSONObject(encoded)
        require(root.getInt("schema") == 1 && root.nullable("account") == account)
        val recent = root.getJSONObject("recent")
        KugouHomeState(
            homeSections = root.array("home") { section -> SourceHomeSection(section.getString("id"), section.getString("title"),
                section.array("tracks", ::readTrack), section.array("collections", ::readCollection),
                section.array("entities") { entity -> SourceEntity(readRef(entity.getJSONObject("ref")), entity.getString("description"),
                    readArtwork(entity.getJSONObject("artwork")), entity.getJSONObject("attributes").strings()) }, readPayload(section.getJSONObject("payload"))) },
            daily = if (root.getString("day") == today()) root.array("daily", ::readTrack) else emptyList(),
            recent = SourceRecentSnapshot(recent.array("tracks", ::readTrack), recent.getInt("totalTracks"),
                recent.array("collections", ::readCollection), recent.getInt("totalCollections"),
                recent.array("albums", ::readCollection), recent.getInt("totalAlbums"), recent.array("radios", ::readCollection)),
            homeDate = root.optString("homeDay").takeIf { it.isNotBlank() },
            homeLoaded = root.getBoolean("homeLoaded"), dailyLoaded = root.getBoolean("dailyLoaded") && root.getString("day") == today(),
            dailyDate = root.getString("day").takeIf { it == today() },
            recentLoaded = root.getBoolean("recentLoaded"))
    }.getOrNull()

    @Synchronized fun save(state: KugouHomeState, check: () -> Unit = {}) {
        val account = requireNotNull(state.owner).accountId
        val recent = state.recent
        val root = JSONObject().put("schema", 1).put("account", account ?: JSONObject.NULL).put("day", state.dailyDate.orEmpty())
            .put("homeDay", state.homeDate.orEmpty()).put("homeLoaded", state.homeLoaded).put("dailyLoaded", state.dailyLoaded).put("recentLoaded", state.recentLoaded)
            .put("home", JSONArray(state.homeSections.map { section -> JSONObject().put("id", section.id).put("title", section.title)
                .put("tracks", JSONArray(section.tracks.map(::writeTrack))).put("collections", JSONArray(section.collections.map(::writeCollection)))
                .put("entities", JSONArray(section.entities.map { entity -> JSONObject().put("ref", writeRef(entity.reference))
                    .put("description", entity.description).put("artwork", writeArtwork(entity.artwork)).put("attributes", JSONObject(entity.attributes)) }))
                .put("payload", writePayload(section.payload)) }))
            .put("daily", JSONArray(state.daily.map(::writeTrack)))
            .put("recent", JSONObject().put("tracks", JSONArray(recent.tracks.map(::writeTrack))).put("totalTracks", recent.totalTracks)
                .put("collections", JSONArray(recent.collections.map(::writeCollection))).put("totalCollections", recent.totalCollections)
                .put("albums", JSONArray(recent.albums.map(::writeCollection))).put("totalAlbums", recent.totalAlbums)
                .put("radios", JSONArray(recent.radios.map(::writeCollection))))
        directory.mkdirs()
        val target = file(account)
        val pending = File("${target.path}.pending")
        val backup = File("${target.path}.bak")
        try {
            pending.writeText(root.toString())
            synchronized(publication) {
                check()
                if (target.exists()) {
                    if (backup.exists()) kotlin.check(backup.delete())
                    kotlin.check(target.renameTo(backup))
                }
                if (!pending.renameTo(target)) { backup.renameTo(target); error("酷狗首页快照保存失败") }
                backup.delete()
            }
        } finally { pending.delete() }
    }

    private fun file(account: String?): File {
        require(account == null || account.toLongOrNull()?.let { it > 0 } == true)
        return File(directory, "account-${account ?: "guest"}.json")
    }
}

internal fun kugouHomeDayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date())

private fun writePayload(v: SourcePayload) = JSONObject().put("format", v.format).put("version", v.version).put("encoded", v.encoded)
private fun readPayload(v: JSONObject) = SourcePayload(v.getString("format"), v.getInt("version"), v.getString("encoded"))
private fun writeIdentity(v: TrackIdentity) = JSONObject().put("source", v.sourceId).put("account", v.accountId ?: JSONObject.NULL).put("remote", v.remoteId)
private fun readIdentity(v: JSONObject) = TrackIdentity(v.getString("source"), v.nullable("account"), v.getString("remote"))
private fun writeArtwork(v: SourceArtwork) = JSONObject().put("url", v.url ?: JSONObject.NULL).put("start", v.startArgb).put("end", v.endArgb).put("mark", v.mark)
private fun readArtwork(v: JSONObject) = SourceArtwork(v.nullable("url"), v.getLong("start"), v.getLong("end"), v.getString("mark"))
private fun writeRef(v: SourceEntityRef) = JSONObject().put("id", v.id).put("name", v.name).put("kind", v.kind).put("payload", writePayload(v.payload))
private fun readRef(v: JSONObject) = SourceEntityRef(v.getString("id"), v.getString("name"), v.getString("kind"), readPayload(v.getJSONObject("payload")))
private fun writeCollection(v: SourceCollection) = JSONObject().put("identity", writeIdentity(v.identity)).put("stable", v.stableId)
    .put("title", v.title).put("subtitle", v.subtitle).put("description", v.description).put("count", v.count ?: JSONObject.NULL)
    .put("artwork", writeArtwork(v.artwork)).put("owned", v.owned).put("favorite", v.accountFavorites).put("payload", writePayload(v.payload))
private fun readCollection(v: JSONObject) = SourceCollection(readIdentity(v.getJSONObject("identity")), v.getString("stable"), v.getString("title"),
    v.getString("subtitle"), v.getString("description"), if (v.isNull("count")) null else v.getInt("count"), readArtwork(v.getJSONObject("artwork")),
    v.getBoolean("owned"), v.getBoolean("favorite"), readPayload(v.getJSONObject("payload")))

internal fun writeTrack(v: SourceTrackDetails): JSONObject = JSONObject().put("identity", writeIdentity(v.track.identity))
    .put("title", v.track.title).put("names", JSONArray(v.track.artists)).put("trackArt", v.track.artworkUrl ?: JSONObject.NULL)
    .put("opaque", JSONObject(v.track.opaqueData)).put("stable", v.stableId).put("album", v.album).put("duration", v.durationMs)
    .put("artwork", writeArtwork(v.artwork)).put("artists", JSONArray(v.artists.map(::writeRef)))
    .put("albumRef", v.albumRef?.let(::writeRef) ?: JSONObject.NULL).put("local", v.localUri).put("payload", writePayload(v.payload))
    .put("artistsText", v.artistsText).put("preview", v.previewUrl).put("aliases", JSONArray(v.identityAliases))
    .put("access", JSONObject().put("badge", v.access.badge?.name ?: JSONObject.NULL).put("trial", v.access.trialAvailable)
        .put("playable", v.access.playable).put("reason", v.access.reason ?: JSONObject.NULL).put("display", v.access.displayBadge?.let { badge ->
            JSONObject().put("text", badge.text).put("lt", badge.lightTextArgb ?: JSONObject.NULL).put("lb", badge.lightBackgroundArgb ?: JSONObject.NULL)
                .put("dt", badge.darkTextArgb ?: JSONObject.NULL).put("db", badge.darkBackgroundArgb ?: JSONObject.NULL) } ?: JSONObject.NULL))
    .put("qualities", JSONArray(v.qualities.map { q -> JSONObject().put("id", q.id).put("label", q.label).put("mime", q.mimeType ?: JSONObject.NULL)
        .put("available", q.available).put("reason", q.reason ?: JSONObject.NULL).put("payload", writePayload(q.payload)) }))
    .put("lyrics", v.lyrics?.let { lyrics -> JSONObject().put("plain", lyrics.plainText ?: JSONObject.NULL)
        .put("lines", JSONArray(lyrics.lines.map { line -> JSONObject().put("start", line.startMs).put("text", line.text)
            .put("translation", line.translation ?: JSONObject.NULL).put("transliteration", line.transliteration ?: JSONObject.NULL).put("parser", line.parserVersion)
            .put("words", JSONArray(line.words.map { word -> JSONObject().put("start", word.startMs).put("duration", word.durationMs).put("text", word.text) })) })) } ?: JSONObject.NULL)

internal fun readTrack(v: JSONObject): SourceTrackDetails {
    val access = v.getJSONObject("access")
    val display = access.optJSONObject("display")
    return SourceTrackDetails(SourceTrack(readIdentity(v.getJSONObject("identity")), v.getString("title"),
        v.getJSONArray("names").stringList(), v.nullable("trackArt"), v.getJSONObject("opaque").strings()), v.getString("stable"), v.getString("album"),
        v.getLong("duration"), readArtwork(v.getJSONObject("artwork")), v.array("artists", ::readRef), v.optJSONObject("albumRef")?.let(::readRef),
        TrackAccessInfo(access.nullable("badge")?.let(TrackAccessBadge::valueOf), access.getBoolean("trial"), access.getBoolean("playable"), access.nullable("reason"),
            display?.let { SourceTrackBadge(it.getString("text"), it.nullableLong("lt"), it.nullableLong("lb"), it.nullableLong("dt"), it.nullableLong("db")) }),
        v.array("qualities") { q -> SourceAudioQuality(q.getString("id"), q.getString("label"), q.nullable("mime"), q.getBoolean("available"), q.nullable("reason"), readPayload(q.getJSONObject("payload"))) },
        v.optJSONObject("lyrics")?.let { lyrics -> SourceLyrics(lyrics.array("lines") { line -> SourceLyricLine(line.getLong("start"), line.getString("text"),
            line.array("words") { word -> SourceLyricWord(word.getLong("start"), word.getLong("duration"), word.getString("text")) },
            line.nullable("translation"), line.nullable("transliteration"), line.getInt("parser")) }, lyrics.nullable("plain")) },
        v.getString("local"), readPayload(v.getJSONObject("payload")), v.getString("artistsText"), v.getString("preview"), v.getJSONArray("aliases").stringList())
}

private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.strings() = keys().asSequence().associateWith { getString(it) }
private fun JSONArray.stringList() = (0 until length()).map(::getString)
private fun <T> JSONObject.array(key: String, read: (JSONObject) -> T): List<T> = getJSONArray(key).let { a -> (0 until a.length()).map { read(a.getJSONObject(it)) } }
