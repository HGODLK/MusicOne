package com.musicone.demo

/** 关联详情收回来源菜单；普通歌单继续让封面按原行程自由拖动。 */
internal fun playlistPullTravel(gestureHeight: Float, hostHeight: Float, entityPage: Boolean): Float =
    (if (entityPage && hostHeight > 0f) hostHeight else gestureHeight).coerceAtLeast(1f) * .65f

/** 双栏左侧信息区独立关闭，右侧歌曲和专辑列表保留滚动。 */
internal fun artistPullRegionAllowed(wide: Boolean, x: Float, width: Int): Boolean =
    !wide || x < width * .43f
