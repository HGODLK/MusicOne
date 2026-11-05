package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 工具顺序固定；确认和提交绝不向模型开放。 */
internal fun aiToolSchema(configuration: AiConfiguration): JSONArray {
    fun string(description: String) = JSONObject().put("type", "string").put("description", description)
    fun strings(description: String) = JSONObject().put("type", "array").put("items", string(description))
    fun boolean(description: String) = JSONObject().put("type", "boolean").put("description", description)
    fun number(description: String) = JSONObject().put("type", "integer").put("description", description)
    val tools = JSONArray()
    fun tool(name: String, description: String, properties: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) {
        tools.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject(properties)).put("required", JSONArray(required)))))
    }
    tool("list_playlists", "分页列出歌单名称、数量、真实类型、平台、账号、归属和权限。整体听歌风格/偏好任务先翻完目录，再挑多个代表歌单抽样并说明范围，不默认只看综合；按需参考可用远端目录，不自动全量逐首。明确单歌单可直接目标，无需用户引用。unknown代表资料未提供。综合是本地自建跨音源收藏，不代表全部资料库。", mapOf("cursor" to string("后续页游标")))
    tool("read_playlist", "读取本地或已订阅歌单的一个快照页。后续使用返回句柄和read_result。", mapOf(
        "playlistId" to string("目录真实ID"), "query" to string("可选歌名/艺人/专辑筛选")), listOf("playlistId"))
    tool("search_library", "在本机歌单内容中筛选歌曲，完整结果保存为句柄，可分页。", mapOf(
        "query" to string("关键词"), "playlistIds" to strings("可选限定歌单ID")), listOf("query"))
    tool("playlist_stats", "本机计算指定歌单计数、交集、并集和重复数量。", mapOf("playlistIds" to strings("歌单ID")), listOf("playlistIds"))
    tool("read_result", "只翻本机已保存结果页；省略cursor读第一页。优先原样调用返回continuation。remoteContinuation指定的远端nextCursor绝不能传给本工具；本机cursor为空不等于远端整单结束。", mapOf(
        "handle" to string("结果句柄"), "cursor" to string("上一页游标")), listOf("handle"))
    tool("compare_results", "本地计算完整歌曲集合的交集intersection、左减右差集difference或并集union，按真实歌曲身份保序去重，不凭同名合并跨源或不同版本。左右分别提供全部实际结果句柄；本地会校验远端从首到末的连续分页，缺页返回recovery，不能用残缺资料比较。结果是完整新句柄，可prepare_add/prepare_create_playlist/prepare_remove。", mapOf(
        "operation" to string("intersection/difference/union"), "leftHandles" to strings("左侧完整集合的全部结果句柄"),
        "rightHandles" to strings("右侧完整集合的全部结果句柄")), listOf("operation", "leftHandles", "rightHandles"))
    tool("read_playback_queue", "只读获取当前歌曲、播放/暂停、循环/随机/电台状态，以及实际顺序的播放队列第一页。playback是capturedAt时刻快照，currentPosition和条目position从1开始；长队列用返回handle及cursor调用read_result翻页，cursor为空才读完。电台只含已加载曲目。再次调用获取最新快照，不改变播放。")
    tool("list_sources", "读取当前启用音源和搜索、远端歌单读取能力。")
    tool("remote_playlists", "只读分页获取音源账号的远端歌单。", mapOf("sourceId" to string("音源ID"),
        "section" to string("CREATED_COLLECTIONS、SAVED_COLLECTIONS或FAVORITE_TRACKS"), "cursor" to string("分页游标")), listOf("sourceId", "section"))
    tool("remote_tracks", "只读分页获取远端歌单，歌曲可用于播放或加入本地；返回nextCursor非空需继续。", mapOf(
        "playlistRef" to string("remote_playlists返回的真实引用"), "cursor" to string("分页游标")), listOf("playlistRef"))
    tool("supplemental_collections", "只读分页浏览补充音源收藏夹/歌单，不修改远端；list_sources声明supplementalCollections时可用。", mapOf(
        "sourceId" to string("真实启用补充音源ID"), "cursor" to string("上一页nextCursor")), listOf("sourceId"))
    tool("supplemental_contents", "只读分页浏览补充源收藏夹内容；每项contentRef可进一步读取实际曲目及完整子项目。", mapOf(
        "collectionRef" to string("补充收藏目录真实引用"), "query" to string("可选筛选"), "cursor" to string("上一页nextCursor")), listOf("collectionRef"))
    tool("supplemental_content", "只读解析补充内容，复用原音源导入转换，不写文件或远端。存在分P/子项目时先省略selectedChildIds获取完整列表，再按用户要求传真实childId；全选须收齐后传全部ID，不将分P规则用于普通单曲。返回trackRefs可经prepare_add/prepare_create_playlist及原生确认加入本地，再按metadataEditable编辑本地显示。", mapOf(
        "contentRef" to string("补充内容真实引用"), "selectedChildIds" to strings("可选真实子项目ID；省略先预览，空列表不代表全选")), listOf("contentRef"))
    val searchProperties = mapOf("query" to string("歌名/艺人/歌单/线索"),
        "sourceId" to string("默认省略，程序按任务选源约束路由；用户指定或后续分页时传对应真实ID，冲突报错，不静默换源"), "cursor" to string("分页游标"),
        "allowOtherSource" to boolean("兼容参数，不构成指定音源授权；仅用户原话明确指定才可绕过自动会员筛选"))
    tool("search_tracks", "用户明确搜索优先；自动先搜当前已确认会员主源，再搜并列二级。未声明登录要求且开放搜索的音源也可参与；sourceResults含资格，播放仍单独检查权益。", searchProperties, listOf("query"))
    tool("search_playlists", "用户明确搜索优先；按资格自动一级再二级搜索真实歌单。未声明登录要求且开放搜索的音源也可参与，各sourceResults返回来源及playlistRef。", searchProperties, listOf("query"))
    tool("show_tracks", "只展示不播放：不足5首用原歌曲长卡；5首及以上自动组成不保存曲库的临时歌单。一条回复最多一个歌单卡，选齐后尽量一次调用。", mapOf(
        "trackRefs" to strings("已返回真实trackRef"), "title" to string("本次推荐的简短歌单标题，与歌曲一并给出，不另调用模型起名"),
        "reason" to string("简短推荐依据")), listOf("trackRefs", "title"))
    tool("show_playlist", "展示一个真实或临时歌单卡片，可点进详情；不保存曲库、不播放。一条回复最多一个歌单卡。", mapOf(
        "playlistRef" to string("搜索、目录或show_tracks返回的真实引用")), listOf("playlistRef"))
    tool("play_tracks", "仅明确播放命令调用，推荐用show_tracks。play立即切歌并保留其他队列及模式；next插在当前后，append追加；replace只限明确替换全队列。预检跳过不可播/未知项，不试播探测。", mapOf(
        "trackRefs" to strings("真实歌曲引用"), "mode" to string("play/next/append/replace"), "title" to string("5首及以上展示临时歌单的标题")), listOf("trackRefs", "mode"))
    tool("play_playlist", "用户明确要求时直接播放真实或临时歌单，同时展示详情卡。默认play保留其他队列及模式；next/append/replace语义同play_tracks。", mapOf(
        "playlistRef" to string("真实歌单引用"), "mode" to string("play/next/append/replace，省略play")), listOf("playlistRef"))
    tool("prepare_edit_tracks", "准备批量修改本地显示歌名、艺人或专辑；仅metadataEditable为true的歌曲可改title/artists/album。只写本地覆盖资料，不改远端，必须原生确认。", mapOf("changes" to JSONObject().put("type", "array").put("items",
        JSONObject().put("type", "object").put("properties", JSONObject().put("trackRef", string("真实歌曲引用"))
            .put("title", string("可选新歌名")).put("artists", string("可选新艺人")).put("album", string("可选新专辑")))
            .put("required", JSONArray(listOf("trackRef"))))), listOf("changes"))
    tool("prepare_rename", "准备重命名批次并弹原生确认，不能直接写入；综合歌单不能重命名。", mapOf("changes" to JSONObject().put("type", "array").put("items",
        JSONObject().put("type", "object").put("properties", JSONObject().put("playlistId", string("真实歌单ID")).put("title", string("新标题")))
            .put("required", JSONArray(listOf("playlistId", "title"))))), listOf("changes"))
    tool("prepare_add", "准备将真实歌曲加入可编辑本地/综合歌单；去重，等待原生确认。trackRefs与resultHandles只选一种；整批优先resultHandles，程序展开句柄全部已保存曲目，无需抄写逐首引用。远端nextCursor非空先继续取得各页句柄，再一次提交全部句柄，不将一页当整单。", mapOf(
        "playlistId" to string("目标歌单ID"), "trackRefs" to strings("逐首选择的真实歌曲引用"),
        "resultHandles" to strings("整批选择：已返回结果句柄，各句柄全部已保存曲目，按传入顺序合并")), listOf("playlistId"))
    tool("prepare_remove", "准备从可编辑本地/综合歌单移除所选成员，必须原生确认；不删歌曲记录或文件、不改远端收藏。重复项使用compare_results交集得到真实结果句柄；trackRefs与resultHandles只选一种。", mapOf(
        "playlistId" to string("目标本地/综合歌单ID"), "trackRefs" to strings("要移出歌单的真实歌曲引用"),
        "resultHandles" to strings("要移出成员的真实结果句柄，优先使用完整比较结果")), listOf("playlistId"))
    tool("prepare_create_playlist", "准备新建普通本地自建歌单并加入这些曲目，必须原生确认。临时歌单后笼统要求加入本地时直接沿原标题和曲目新建，无需追问已有歌单。", mapOf(
        "title" to string("用户指定标题或临时歌单原标题"), "trackRefs" to strings("逐首真实歌曲引用，与resultHandles只选一种"),
        "resultHandles" to strings("整批选择优先使用已返回句柄，先取得所有远端页；本地展开全部已保存曲目，与trackRefs只选一种")), listOf("title"))
    tool("prepare_delete_playlist", "准备删除普通本地自建歌单，必须原生确认；综合、手机存储、订阅、远端和派生歌单不能删除。", mapOf(
        "playlistId" to string("目录真实歌单ID")), listOf("playlistId"))
    tool("prepare_undo", "为已执行的本助手批次生成撤销计划，仍需原生确认。", mapOf("operationId" to string("原生执行返回的批次ID")), listOf("operationId"))
    tool("read_lyrics", "只读一首歌当前显示的歌词：行号index从0开始，含原文、时间、已有翻译/注音及AI叠加内容；lyrics为ai_written/original/none。长歌词按cursor续读。", mapOf(
        "trackRef" to string("真实歌曲引用"), "cursor" to string("上一页游标")), listOf("trackRef"))
    tool("prepare_lyric_annotations", "按行号给现有歌词加翻译或注音，必须原生确认；不能改原文、时间和逐字。已有内容的行默认跳过，用户明确要求替换才传replace。ruby每行写成整行原文并用{原文片段|读音}标注，如{今日|きょう}は{晴|は}れ，去掉标注须与原文逐字一致；读音可为假名、拼音或罗马音，音之间可用空格。", mapOf(
        "trackRef" to string("真实歌曲引用"), "kind" to string("translation或ruby"),
        "lines" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties",
            JSONObject().put("index", number("read_lyrics行号")).put("text", string("译文或带标注的整行注音")))
            .put("required", JSONArray(listOf("index", "text")))),
        "replace" to boolean("仅用户明确要求覆盖已有翻译/注音时为true")), listOf("trackRef", "kind", "lines"))
    tool("prepare_write_lyrics", "为歌曲写入AI歌词（独立保存，原歌词文件不动），必须原生确认。来源优先级：source（搜索到的其他音源同一首歌，程序直接取其歌词，一轮搜索即可）> user_provided（用户在对话中贴出的歌词）> web（本轮web_fetch抓取的网页原文，最多搜两次抓两次）> model（仅用户原话坚持要AI写时）。程序核对来源，不符即拒绝。source来源由程序原样取对方歌词，含逐行与逐字时间（不需提交lyrics），是补逐字歌词的唯一途径。其余来源的lyrics每行都带[mm:ss.xx]或都不带（不带时按时长估算），行时间须来自来源原文、不能自编，不能带逐字时间。已有歌词时仅用户明确要求替换才传replace。返回warning须如实转告用户。", mapOf(
        "trackRef" to string("要写入的真实歌曲引用"), "origin" to string("source/user_provided/web/model"),
        "sourceTrackRef" to string("source来源：其他音源同一首歌的trackRef"), "lyrics" to string("非source来源的歌词全文"),
        "evidence" to string("web填抓取网址；model原样引用用户要求AI写歌词的那句话"),
        "replace" to boolean("仅用户明确要求替换已有歌词时为true")), listOf("trackRef", "origin"))
    tool("prepare_remove_lyrics", "删除AI写入的歌词或AI加的翻译/注音，或取消手动关联的歌词（文件保留），必须原生确认；不能删除原歌词文件。", mapOf(
        "trackRef" to string("真实歌曲引用"), "target" to string("ai_lyrics/translation/ruby/manual_link")), listOf("trackRef", "target"))
    tool("save_task", "持续保存超大歌单任务目标、约束、游标、覆盖数量、分批结论及未完成事项，供压缩/退出后继续。", mapOf(
        "notes" to string("结构清晰的任务记录，不包含私有凭证；应含所有待办和分批结论"),
        "processed" to number("已处理歌曲数"), "total" to number("已知总数")), listOf("notes"))
    tool("read_task", "分页读取完整持久任务记录、用户要求和最近结果句柄/游标。", mapOf("cursor" to string("上一页返回的游标")))
    // 聊天供应商与网页工具独立；未配置 DeepSeek 时不向模型开放网页工具。
    if (configuration.webSearchEnabled) {
        tool("web_search", "使用DeepSeek官方独立搜索接口查询公开网页。", mapOf("query" to string("搜索关键词")), listOf("query"))
        tool("web_fetch", "使用DeepSeek官方独立抓取接口读取公开网页。", mapOf("url" to string("网页URL")), listOf("url"))
    }
    return tools
}
