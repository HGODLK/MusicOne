package com.musicone

import kotlinx.coroutines.CancellationException

internal const val LOCAL_TRACK_COLOR_ARGB = 0xFF9B8AC4L

/** 曲目色不借用页面色；透明色与超出 ARGB 范围的值视为未声明。 */
internal fun validTrackThemeArgb(value: Long?): Long? = value?.takeIf {
    it in 0..0xFFFFFFFFL && (it ushr 24) != 0L
}

internal fun fallbackTrackThemeArgb(local: Boolean, darkBackground: Boolean): Long =
    if (local) LOCAL_TRACK_COLOR_ARGB else if (darkBackground) 0xFFFFFFFFL else 0xFF000000L

/** 即使外包忽略取消，旧请求也不能覆盖换曲、换账号或换运行实例后的颜色。 */
internal class CurrentTrackThemeResolver {
    private var generation = 0L

    fun invalidate() { generation++ }

    suspend fun resolve(
        local: Boolean,
        isCurrent: () -> Boolean,
        load: suspend () -> Long?,
        pendingArgb: Long? = null,
        publish: (Long?) -> Unit,
    ) {
        val request = ++generation
        publish(if (local) LOCAL_TRACK_COLOR_ARGB else validTrackThemeArgb(pendingArgb))
        if (local) return
        val value = try { validTrackThemeArgb(load()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (request == generation && isCurrent()) publish(value)
    }
}
