package com.musicone

/** 点击首页推荐歌曲后的三种处理方式。 */
internal enum class HomeRecommendationAction { JOIN_RADIO, START_RADIO, INSERT_IN_QUEUE }

/**
 * 同音源电台中始终留在电台内；跨音源时电台不能混入其他来源，开关关闭时换成新音源的电台。
 * 开关开启且已有队列时只插入队列；没有队列可替换时仍开电台，避免只有一首歌就停下。
 * 音源没有电台时只能走队列，否则起播后补歌必然失败。
 */
internal fun homeRecommendationAction(
    trackSource: MusicSource,
    radioActive: Boolean,
    radioSource: MusicSource,
    hasQueue: Boolean,
    keepQueue: Boolean,
    radioAvailable: Boolean,
): HomeRecommendationAction = when {
    radioActive && radioSource == trackSource -> HomeRecommendationAction.JOIN_RADIO
    !radioAvailable || (keepQueue && hasQueue) -> HomeRecommendationAction.INSERT_IN_QUEUE
    else -> HomeRecommendationAction.START_RADIO
}

/**
 * 首页货架点歌入队的歌曲：从点击的那首起取到本页末尾，点中间一首时上面的不入队。
 * 只保留与点击歌曲同音源、当前可播放的歌，点击的那首始终在第一位。
 */
internal fun homeRecommendationBatch(
    tracks: List<MusicTrack>,
    index: Int,
    available: (MusicTrack) -> Boolean,
): List<MusicTrack> {
    val selected = tracks.getOrNull(index) ?: return emptyList()
    val following = tracks.drop(index + 1).filter { it.source == selected.source && available(it) }
    return (listOf(selected) + following).distinctBy { it.source to it.id }
}

/**
 * 把整批歌曲接在当前歌曲之后：队列中已有的同一首先移走，当前歌曲之后原有的歌顺延到这批之后。
 * 当前歌曲本身在这批里时也随这批重新排位。
 */
internal fun insertTracksAfterCurrent(
    queue: List<MusicTrack>,
    current: MusicTrack?,
    batch: List<MusicTrack>,
): List<MusicTrack> {
    fun MusicTrack.key() = source to id
    val keys = batch.mapTo(hashSetOf()) { it.key() }
    val currentIndex = current?.let { active -> queue.indexOfFirst { it.key() == active.key() } } ?: -1
    val head = if (currentIndex >= 0) queue.take(currentIndex + 1) else listOfNotNull(current)
    val tail = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue
    return (head.filterNot { it.key() in keys } + batch + tail.filterNot { it.key() in keys }).distinctBy { it.key() }
}
