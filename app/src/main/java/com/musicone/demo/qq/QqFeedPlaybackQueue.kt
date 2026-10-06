package com.musicone.demo

/** 卡片点播插到当前曲之后；已排队的同一曲移到此处，保留历史与后续推荐。 */
internal fun insertQqFeedTrack(queue: List<MusicTrack>, current: MusicTrack?, selected: MusicTrack): List<MusicTrack> {
    return insertTrackAfterCurrent(queue, current, selected)
}
