package com.musicone

import androidx.compose.runtime.*

/** 菜单从出现到退场结束共同持有方向锁，子菜单关闭不会释放其他菜单的锁。 */
@Stable
internal class PhonePlayerMenuLocks(private val turn: PhonePlayerTurn) {
    private val owners = mutableStateMapOf<Any, Boolean>()
    private val turnOwner = Any()
    private var holdsTurn = false
    val active: Boolean get() = owners.isNotEmpty()

    fun set(owner: Any, visible: Boolean) {
        val wasActive = active
        if (visible) owners[owner] = true else owners.remove(owner)
        if (!wasActive && active) {
            // 静止页面只锁定方向请求，不封存歌词阅读枢轴，否则下一句滚动会被整窗反向补偿。
            holdsTurn = turn.isTurning
            if (holdsTurn) turn.hold(turnOwner)
        } else if (wasActive && !active) {
            if (holdsTurn) turn.resume(turnOwner)
            holdsTurn = false
        }
    }

    fun reset() {
        owners.clear()
        if (holdsTurn) turn.resume(turnOwner)
        holdsTurn = false
    }
}

@Composable
internal fun PhonePlayerMenuLock(owner: Any, visible: Boolean) {
    val locks = LocalPhonePlayerPresentation.current?.takeIf { it.isPhone }?.menuLocks
    SideEffect { locks?.set(owner, visible) }
    DisposableEffect(locks, owner) { onDispose { locks?.set(owner, false) } }
}
