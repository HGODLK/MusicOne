package com.musicone.source.sdk

/** 一次解析的歌曲直接用于公共选择和导入；被淘汰的临时结果按原输入重新解析。 */
internal class SimpleSupplementalContents(
    private val sourceId: String,
    private val parse: suspend (String) -> List<SourceTrackDetails>,
) : SourceContents, SourceChildItems, SourceImporting {
    private val snapshots = LinkedHashMap<TrackIdentity, List<SourceTrackDetails>>()

    override suspend fun resolve(input: String): SourceContent {
        require(input.isNotBlank()) { "请输入链接或编号" }
        val tracks = parse(input)
        require(tracks.isNotEmpty()) { "没有解析到可导入的歌曲" }
        require(tracks.all { it.track.identity.sourceId == sourceId && it.stableId.isNotBlank() }) { "解析结果缺少稳定身份或属于其他音源" }
        require(tracks.map { it.track.identity }.distinct().size == tracks.size && tracks.map { it.stableId }.distinct().size == tracks.size) { "解析结果包含重复歌曲身份" }
        val first = tracks.first()
        val identity = TrackIdentity(sourceId, first.track.identity.accountId, "input:$input")
        synchronized(snapshots) {
            snapshots.remove(identity)
            snapshots[identity] = tracks
            while (snapshots.size > 32) snapshots.remove(snapshots.keys.first())
        }
        return SourceContent(identity, first.track.title, artwork = first.artwork,
            durationMs = tracks.sumOf { it.durationMs }, payload = SourcePayload(INPUT_FORMAT, 1, input),
            playableTrack = tracks.singleOrNull())
    }

    override suspend fun detail(content: SourceContent): SourceContent {
        if (content.payload.format != INPUT_FORMAT) return content
        val tracks = snapshot(content)
        // 缓存淘汰后的新解析同时更新内容资料及单曲入口，不能继续沿用旧曲目。
        return content.copy(title = tracks.first().track.title, artwork = tracks.first().artwork,
            durationMs = tracks.sumOf { it.durationMs }, playableTrack = tracks.singleOrNull())
    }

    override suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem> {
        if (request.cursor != null) return SourcePage(emptyList(), null)
        val tracks = snapshot(content)
        if (tracks.size == 1) return SourcePage(emptyList(), null)
        return SourcePage(tracks.mapIndexed { index, track ->
            SourceChildItem(track.track.identity, index + 1, track.track.title, track.durationMs, initiallySelected = true)
        }, null)
    }

    override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails> {
        val selected = selectedChildren.map { it.identity }.toSet()
        val tracks = snapshot(content)
        require(selected.all { id -> tracks.any { it.track.identity == id } }) { "所选歌曲已变化，请重新解析" }
        if (selected.isEmpty() && tracks.size == 1) return tracks
        return tracks.filter { it.track.identity in selected }
    }

    private suspend fun snapshot(content: SourceContent): List<SourceTrackDetails> {
        require(content.identity.sourceId == sourceId) { "不能解析其他音源的内容" }
        synchronized(snapshots) { snapshots[content.identity]?.let { return it } }
        require(content.payload.format == INPUT_FORMAT && content.payload.version == 1) { "此内容需要音源提供自定义转换服务" }
        val refreshed = resolve(content.payload.encoded)
        require(refreshed.identity == content.identity) { "内容账号已变化，请重新解析" }
        return synchronized(snapshots) { checkNotNull(snapshots[refreshed.identity]) }
    }

    fun clear() = synchronized(snapshots) { snapshots.clear() }
    private companion object { const val INPUT_FORMAT = "musicone.simple.input" }
}
