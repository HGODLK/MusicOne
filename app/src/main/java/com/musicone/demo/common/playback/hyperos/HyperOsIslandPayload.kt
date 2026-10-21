package com.musicone.demo

import org.json.JSONObject

internal const val HYPER_OS_COVER_KEY = "miui.focus.pic_musicone_cover"
internal const val HYPER_OS_CUSTOM_PARAM = "miui.focus.param.custom"
internal const val HYPER_OS_STANDARD_PARAM = "miui.focus.param"
internal const val HYPER_OS_TICKER = "miui.focus.ticker"
internal val HYPER_OS_CARD_LAYOUT_KEYS = listOf("miui.focus.rv", "miui.focus.rvNight", "miui.focus.rv.island.expand")
internal val HYPER_OS_OVERLAY_KEYS = HYPER_OS_CARD_LAYOUT_KEYS + listOf(HYPER_OS_CUSTOM_PARAM,
    HYPER_OS_STANDARD_PARAM, HYPER_OS_TICKER, "miui.focus.pics", "miui.focus.actions", HYPER_OS_FOREGROUND_KEY)

/** 自定义协议直接携带参数，展开卡片由同一通知中的 RemoteViews 提供。 */
internal fun hyperOsIslandPayload(summary: HyperOsIslandSummary, sequence: Long, bringToFront: Boolean): String {
    fun text(title: String, detail: String = "") = JSONObject()
        .put("title", title)
        .put("showHighlightColor", false).put("narrowFont", false)
        .apply { if (detail.isNotBlank()) put("content", detail) }
    fun picture() = JSONObject().put("type", 1).put("pic", HYPER_OS_COVER_KEY)
    val params = JSONObject()
        .put("business", "lyric_display").put("sequence", sequence)
        .put("updatable", true).put("reopen", "reopen")
        .put("enableFloat", false).put("islandFirstFloat", false)
        .put("isShowNotification", true).put("filterWhenNoPermission", false)
        .put("ticker", summary.lyric).put("aodTitle", summary.lyric)
        .put("aodPic", HYPER_OS_COVER_KEY)
        .put("param_island", JSONObject()
            .put("islandProperty", 1).put("islandOrder", bringToFront)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1)
                    .put("picInfo", picture()).put("textInfo", text(summary.title, summary.artist)))
                .put("textInfo", text(summary.lyric)))
            .put("smallIslandArea", JSONObject().put("picInfo", picture())))
    return params.toString()
}

/** 标准协议与自定义协议分别构建，展开模板只显示歌曲资料，不把歌词带进展开区。 */
internal fun hyperOsStandardIslandPayload(frame: HyperOsIslandFrame, sequence: Long, bringToFront: Boolean): String {
    val params = JSONObject(hyperOsIslandPayload(frame.summary, sequence, bringToFront))
        .put("protocol", 1)
        .put("baseInfo", JSONObject().put("type", 1).put("title", frame.title).put("content", frame.artist))
        .put("picInfo", JSONObject().put("type", 2).put("pic", HYPER_OS_COVER_KEY))
    return JSONObject().put("param_v2", params).toString()
}
