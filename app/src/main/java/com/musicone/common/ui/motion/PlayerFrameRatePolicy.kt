package com.musicone

/** 只限制封面空闲态的隐藏歌词；UI 始终采用全局设置。 */
internal class PlayerFrameRatePolicy {
    private val idle = mutableMapOf<Any, () -> Boolean>()
    private val busy = mutableMapOf<Any, () -> Boolean>()

    fun registerIdle(owner: Any, condition: () -> Boolean) { idle[owner] = condition }
    fun registerBusy(owner: Any, condition: () -> Boolean) { busy[owner] = condition }
    fun remove(owner: Any) { idle.remove(owner); busy.remove(owner) }

    val isIdle: Boolean get() = idle.values.any { it() } && busy.values.none { it() }
    var lyricFrameNanos: Long = Long.MIN_VALUE
        private set

    fun onLyricFrame(time: Long) { lyricFrameNanos = time }
    fun resolve(configured: AnimationFrameRate): AnimationFrameRate =
        configured
    fun resolveLyrics(configured: AnimationFrameRate): Int = if (isIdle) 8 else configured.fps
}
