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
