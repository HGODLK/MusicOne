package com.musicone.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * B站协议接口客户端。
 *
 * 聚合登录鉴权、收藏夹分页拉取、分P信息与 DASH 音频播放取票。
 */
internal class BiliApi(context: Context) {
    private val auth = BiliAuthApi(context)

    // ── 转发鉴权接口 ──────────────────────────────────────────────────────────

    fun generateQrCode(): BiliQrCode = auth.generateQrCode()
    fun pollQrCode(qrcodeKey: String): BiliQrStatus = auth.pollQrCode(qrcodeKey)
    fun getUserInfo(cookie: String): BiliAccountSession? = auth.getUserInfo(cookie)
    fun sendSmsCode(cid: String, phone: String, geetest: BiliGeetestResult? = null): BiliSmsSendResult =
        auth.sendSmsCode(cid, phone, geetest)
    fun loginBySms(cid: String, phone: String, code: String, captchaKey: String): BiliAccountSession =
        auth.loginBySms(cid, phone, code, captchaKey)

    // ── 收藏夹与分P接口 ──────────────────────────────────────────────────────────

    fun getFavoriteFolders(mid: Long, cookie: String): List<BiliFavFolder> {
        val headers = auth.webHeaders(cookie)
        val resp = PlatformHttp.get("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid", headers = headers)
        val json = JSONObject(resp.text)
        if (json.optInt("code") != 0) throw PlatformApiException(json.optString("message", "获取收藏夹失败"))
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()

        return buildList {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                add(BiliFavFolder(
                    id = item.getLong("id"),
                    title = item.optString("title", "未命名收藏夹"),
                    mediaCount = item.optInt("media_count", 0),
                    cover = item.optString("cover", ""),
                ))
            }
        }
    }

    fun getFavoriteFolderCover(folderId: Long, cookie: String): String {
        val response = PlatformHttp.get("https://api.bilibili.com/x/v3/fav/folder/info?media_id=$folderId",
            headers = auth.webHeaders(cookie))
        val json = JSONObject(response.text)
        if (json.optInt("code") != 0) throw PlatformApiException(json.optString("message", "读取收藏夹封面失败"))
        return json.optJSONObject("data")?.optString("cover").orEmpty()
    }

    fun getFavoriteVideos(folderId: Long, page: Int, cookie: String, query: String = ""): BiliFavVideoPage {
        val headers = auth.webHeaders(cookie)
        val keyword = query.trim().takeIf(String::isNotEmpty)?.let { "&keyword=${it.urlEncoded()}" }.orEmpty()
        val resp = PlatformHttp.get("https://api.bilibili.com/x/v3/fav/resource/list?media_id=$folderId&pn=$page&ps=20&platform=web$keyword", headers = headers)
        val json = JSONObject(resp.text)
        val code = json.optInt("code")
        if (code != 0) throw PlatformApiException(json.optString("message", "加载收藏夹内容失败(code=$code)"))
        val data = json.optJSONObject("data") ?: throw PlatformApiException("收藏夹返回数据为空")
        require(data.has("has_more")) { "收藏夹分页信息缺失" }
        val array = data.optJSONArray("medias")
        val videos = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val upper = item.optJSONObject("upper")
                    val attr = item.optInt("attr", 0)
                    val isInvalid = (attr and 1) != 0 || item.optString("title") == "已失效视频"
                    add(BiliVideoItem(
                        aid = item.optLong("id", 0L),
                        bvid = item.optString("bvid", ""),
                        cid = item.optLong("cid", 0L),
                        title = item.optString("title", if (isInvalid) "已失效视频" else ""),
                        cover = item.optString("cover", ""),
                        upName = upper?.optString("name", "哔哩哔哩") ?: "哔哩哔哩",
                        durationSec = item.optLong("duration", 0L),
                        isInvalid = isInvalid,
                    ))
                }
            }
        }
        return BiliFavVideoPage(videos = videos, hasMore = data.optBoolean("has_more", false))
    }

    fun getVideoPages(bvid: String, cookie: String = ""): List<BiliPagePart> {
        val headers = auth.webHeaders(cookie)
        val resp = PlatformHttp.get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid", headers = headers)
        val json = JSONObject(resp.text)
        val code = json.optInt("code")
        if (code != 0) throw PlatformApiException(json.optString("message", "获取视频分P失败(code=$code)"))
        val array = json.optJSONArray("data") ?: throw PlatformApiException("视频分P列表为空")
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(BiliPagePart(
                    cid = item.getLong("cid"),
                    page = item.optInt("page", i + 1),
                    part = item.optString("part", "P${i + 1}"),
                    durationMs = item.optLong("duration", 0L) * 1000L,
                ))
            }
        }
    }

    /**
     * 获取视频真实详情（包含真实标题、UP主、封面、时长及分P，拒绝虚假兜底）。
     */
    fun getVideoDetails(bvid: String, cookie: String = ""): BiliVideoDetails {
        val headers = auth.webHeaders(cookie)
        val resp = PlatformHttp.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers = headers)
        val json = JSONObject(resp.text)
        val code = json.optInt("code")
        if (code != 0) throw PlatformApiException(json.optString("message", "获取视频详情失败(code=$code)"))
        val data = json.optJSONObject("data") ?: throw PlatformApiException("视频详情数据为空")
        val owner = data.optJSONObject("owner")
        val pagesArray = data.optJSONArray("pages")
        val pages = buildList {
            if (pagesArray != null) {
                for (i in 0 until pagesArray.length()) {
                    val p = pagesArray.getJSONObject(i)
                    add(BiliPagePart(
                        cid = p.getLong("cid"),
                        page = p.optInt("page", i + 1),
                        part = p.optString("part", "P${i + 1}"),
                        durationMs = p.optLong("duration", 0L) * 1000L,
                    ))
                }
            }
        }
        return BiliVideoDetails(
            aid = data.optLong("aid", 0L),
            bvid = data.optString("bvid", bvid),
            title = data.optString("title", ""),
            cover = data.optString("pic", ""),
            upName = owner?.optString("name", "哔哩哔哩") ?: "哔哩哔哩",
            upMid = owner?.optLong("mid", 0L) ?: 0L,
            durationSec = data.optLong("duration", 0L),
            pages = pages,
        )
    }

    // ── 播放取票 (DASH) ─────────────────────────────────────────────────────

    fun getAudioStreams(bvid: String, cid: Long, cookie: String): BiliPlayAudioResult {
        val rawParams = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to "127",
            "fnval" to "4048",
            "fourk" to "1",
        )
        // 与 BiliOne 一致，普通视频取票不额外依赖 nav 与 WBI。
        val query = rawParams.entries.joinToString("&") { "${BiliSigner.encode(it.key)}=${BiliSigner.encode(it.value)}" }
        val headers = auth.webHeaders(cookie, "https://www.bilibili.com/video/$bvid")
        val resp = biliPlaybackStage("获取音频地址") { PlatformHttp.get("https://api.bilibili.com/x/player/playurl?$query", headers = headers) }
        val json = JSONObject(resp.text)
        if (json.optInt("code") != 0) throw PlatformApiException(json.optString("message", "获取播放地址失败"))
        val data = json.getJSONObject("data")
        val dash = data.optJSONObject("dash") ?: throw PlatformApiException("平台未返回DASH流数据")
        val durationMs = data.optLong("timelength", 0L)

        val defaultAudio = parseAudioStream(dash.optJSONArray("audio"))
        val dolbyAudio = dash.optJSONObject("dolby")?.optJSONArray("audio")?.let { parseAudioStream(it) }
        val hiResAudio = dash.optJSONObject("flac")?.optJSONObject("audio")?.let { parseSingleAudioStream(it) }

        return BiliPlayAudioResult(
            defaultAudio = defaultAudio,
            hiResAudio = hiResAudio,
            dolbyAudio = dolbyAudio,
            durationMs = durationMs,
        )
    }

    private fun parseAudioStream(array: JSONArray?): BiliAudioStream? {
        if (array == null || array.length() == 0) return null
        var best: BiliAudioStream? = null
        for (i in 0 until array.length()) {
            val item = parseSingleAudioStream(array.getJSONObject(i)) ?: continue
            if (best == null || item.bandwidth > best.bandwidth) best = item
        }
        return best
    }

    private fun parseSingleAudioStream(obj: JSONObject): BiliAudioStream? {
        val baseUrl = obj.optString("baseUrl").ifBlank { obj.optString("base_url") }
        if (baseUrl.isBlank()) return null
        val backups = buildList {
            obj.optJSONArray("backupUrl")?.let { arr ->
                for (j in 0 until arr.length()) add(arr.getString(j))
            }
            obj.optJSONArray("backup_url")?.let { arr ->
                for (j in 0 until arr.length()) add(arr.getString(j))
            }
        }
        val seg = obj.optJSONObject("SegmentBase") ?: obj.optJSONObject("segment_base") ?: return null
        val codecs = obj.optString("codecs").takeIf(String::isNotBlank) ?: return null
        val initialization = seg.optString("Initialization", seg.optString("initialization", ""))
        val index = seg.optString("indexRange", seg.optString("index_range", ""))
        if (initialization.isBlank() || index.isBlank()) return null
        return BiliAudioStream(
            id = obj.optInt("id", 30280),
            baseUrl = baseUrl,
            backupUrls = backups,
            bandwidth = obj.optLong("bandwidth", 0L),
            codecs = codecs,
            mimeType = obj.optString("mimeType", obj.optString("mime_type", "audio/mp4")),
            initializationRange = initialization,
            indexRange = index,
        )
    }
}

private inline fun <T> biliPlaybackStage(stage: String, block: () -> T): T = try { block() } catch (error: PlatformApiException) {
    throw PlatformApiException("B站${stage}失败：${error.message}", error.apiCode)
}
