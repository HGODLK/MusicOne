package com.musicone

/** 我喜欢按钮写入目标；仅对之后点击生效，未声明我喜欢能力的音源始终默认进入综合歌单。 */
internal enum class FavoriteButtonBehavior(
    val storedValue: String,
    val label: String,
    val description: String,
) {
    SOURCE_ONLY(
        storedValue = "source_only",
        label = "仅对应音源内“我喜欢”",
        description = "写入歌曲所属音源的“我喜欢”",
    ),
    BOTH(
        storedValue = "both",
        label = "“综合歌单”和“我喜欢”",
        description = "同时写入综合歌单和对应音源的“我喜欢”",
    ),
    UNIFIED_ONLY(
        storedValue = "unified_only",
        label = "仅“综合歌单”",
        description = "只写入本地综合歌单，不同步到音源",
    );

    companion object {
        fun fromStored(value: String?): FavoriteButtonBehavior =
            entries.firstOrNull { it.storedValue == value } ?: SOURCE_ONLY
    }
}

internal data class FavoriteTogglePlan(
    val remoteTarget: Boolean? = null,
    val unifiedTarget: Boolean? = null,
)

/** 计算哪些歌要亮红心：只写音源模式下，只有不支持云端红心的音源才把综合歌单里的歌算进来，避免串扰。 */
internal fun displayedFavoriteIds(
    remoteIds: Set<String>,
    unifiedTracks: List<MusicTrack>,
    behavior: FavoriteButtonBehavior,
    supportsRemoteFavorites: (MusicSource) -> Boolean,
): Set<String> {
    if (behavior == FavoriteButtonBehavior.UNIFIED_ONLY) {
        return buildSet { unifiedTracks.forEach { addAll(playlistTrackIdentities(it)) } }
    }
    val localIds = buildSet {
        unifiedTracks.forEach { track ->
            if (behavior == FavoriteButtonBehavior.BOTH || !supportsRemoteFavorites(track.source)) {
                addAll(playlistTrackIdentities(track))
            }
        }
    }
    return if (localIds.isEmpty()) remoteIds else remoteIds + localIds
}

/** 按按钮设置与音源能力决定本次点击写入目标；双写模式先按当前展示状态取反，避免两边方向相反。 */
internal fun planFavoriteToggle(
    track: MusicTrack,
    behavior: FavoriteButtonBehavior,
    supportsRemote: Boolean,
    remoteIds: Set<String>,
    unifiedIds: Set<String>,
): FavoriteTogglePlan {
    val inUnified = track.isMainSourceFavorite(unifiedIds)
    if (!supportsRemote || behavior == FavoriteButtonBehavior.UNIFIED_ONLY) {
        return FavoriteTogglePlan(unifiedTarget = !inUnified)
    }
    val inRemote = track.isMainSourceFavorite(remoteIds)
    return when (behavior) {
        FavoriteButtonBehavior.SOURCE_ONLY -> FavoriteTogglePlan(remoteTarget = !inRemote)
        FavoriteButtonBehavior.BOTH -> {
            val desired = !(inRemote || inUnified)
            FavoriteTogglePlan(
                remoteTarget = desired.takeIf { it != inRemote },
                unifiedTarget = desired.takeIf { it != inUnified },
            )
        }
        FavoriteButtonBehavior.UNIFIED_ONLY -> FavoriteTogglePlan(unifiedTarget = !inUnified)
    }
}
