package com.musicone.source.kugou

import java.net.URI
import org.json.JSONObject

/** 只规范平台实际返回的图片地址，不用歌手或专辑编号拼造图片。 */
internal fun String?.normalizeKugouContentArtwork(size: Int = 480): String? {
    require(size > 0)
    val value = this?.trim()?.takeIf { it.isNotBlank() && it !in setOf("null", "undefined", "0") }
        ?.replace("\\/", "/")?.replace("&amp;", "&")?.replace("{size}", size.toString()) ?: return null
    val absolute = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", true) -> "https://" + value.substring(7)
        else -> value
    }
    val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
    return absolute.takeIf { uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() }
}

/** 搜索 Image、专辑 album_info.cover 和 union_cover 均由成熟客户端实际消费。 */
internal fun JSONObject.kugouContentTrackArtwork(): String? =
    contentImage("Image", "image", "ImgUrl", "imgurl", "img", "cover", "sizable_cover")
        ?: optJSONObject("album_info")?.contentImage("cover", "sizable_cover", "imgurl", "img")
        ?: optJSONObject("trans_param")?.contentImage("union_cover")
        ?: optJSONObject("TransParam")?.contentImage("union_cover")
        ?: optJSONObject("audio_info")?.let { audio ->
            audio.contentImage("Image", "image", "cover", "sizable_cover")
                ?: audio.optJSONObject("trans_param")?.contentImage("union_cover")
        }

/** 歌手详情的 sizable_avatar 与专辑搜索的 img 是不同字段，不能只读一套别名。 */
internal fun JSONObject.kugouContentEntityArtwork(kind: String): String? =
    if (kind == "artist") contentImage("sizable_avatar", "SizableAvatar", "Avatar", "avatar", "ImgUrl", "imgurl", "image", "img")
    else contentImage("sizable_cover", "ImgUrl", "imgurl", "img", "Image", "image", "cover", "pic")

/** 酷狗已确认的资料只有平台头像时，用同源图片作为背景，不伪造官方写真或轮播图。 */
internal fun JSONObject.kugouContentArtistBackgrounds(): List<String> =
    listOfNotNull(kugouContentEntityArtwork("artist"))

private fun JSONObject.contentImage(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().normalizeKugouContentArtwork()
}
