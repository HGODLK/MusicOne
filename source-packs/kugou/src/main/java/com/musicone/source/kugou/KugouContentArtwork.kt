package com.musicone.source.kugou

import java.net.URI
import org.json.JSONObject

/** 补全和格式化酷狗返回的图片 URL（替换 {size} 等），不自己拿 ID 拼接链接。 */
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

/** 按优先级从搜索结果、专辑信息和 union_cover 里提取歌曲封面 URL。 */
internal fun JSONObject.kugouContentTrackArtwork(): String? =
    contentImage("Image", "image", "ImgUrl", "imgurl", "img", "cover", "sizable_cover")
        ?: optJSONObject("album_info")?.contentImage("cover", "sizable_cover", "imgurl", "img")
        ?: optJSONObject("trans_param")?.contentImage("union_cover")
        ?: optJSONObject("TransParam")?.contentImage("union_cover")
        ?: optJSONObject("audio_info")?.let { audio ->
            audio.contentImage("Image", "image", "cover", "sizable_cover")
                ?: audio.optJSONObject("trans_param")?.contentImage("union_cover")
        }

/** 歌手详情用 sizable_avatar，专辑搜索用 img，这里按类型分别取对应字段。 */
internal fun JSONObject.kugouContentEntityArtwork(kind: String): String? =
    if (kind == "artist") contentImage("sizable_avatar", "SizableAvatar", "Avatar", "avatar", "ImgUrl", "imgurl", "image", "img")
    else contentImage("sizable_cover", "ImgUrl", "imgurl", "img", "Image", "image", "cover", "pic")

/** 没拿到歌手写真时，退回用歌手头像当背景图。 */
internal fun JSONObject.kugouContentArtistBackgrounds(): List<String> =
    listOfNotNull(kugouContentEntityArtwork("artist"))

private fun JSONObject.contentImage(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().normalizeKugouContentArtwork()
}
