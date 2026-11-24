package com.musicone.source.qq.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** 监听默认网络恢复（后台醒来、切换网络），用来提前复核失效的登录；无法注册时返回 null。 */
internal fun qqNetworkAvailability(context: Context, onAvailable: () -> Unit): AutoCloseable? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }
    return runCatching { manager.registerDefaultNetworkCallback(callback) }.map {
        AutoCloseable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.getOrNull()
}
