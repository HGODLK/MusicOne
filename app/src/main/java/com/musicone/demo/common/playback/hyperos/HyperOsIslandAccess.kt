package com.musicone.demo

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.annotation.ChecksSdkIntAtLeast

/** 按系统协议识别 HyperOS 3 及以上，不以官方准入查询拦截直发。 */
internal object HyperOsIslandAccess {
    @ChecksSdkIntAtLeast(api = 26)
    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 && runCatching {
        // 系统返回 1／2／3 分别对应 HyperOS 1／2／3，缺失或读取失败时不开放。
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) >= 3
    }.getOrDefault(false)

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < 24 || context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
}
