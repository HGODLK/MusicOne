package com.musicone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 回复期间独立保活，不绑定助手页面或播放器；完成和等待原生确认后立即结束。 */
class AiReplyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "AI 回复任务", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MusicOne 正在准备回复")
            .setContentText("离开 Mio 或切到后台后仍会继续处理")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        // 锁的寿命只覆盖这次服务，不使用与模型生成长度无关的固定短超时。
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicOne:AiReply").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            AiAssistantController.get(applicationContext).state.first { !it.running }
            stopSelfResult(startId)
        }
        // 不自行重放工具；系统回收后的恢复仍沿已保存的会话断点。
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AiAssistantController.get(applicationContext).dispatch(AiUiAction.Stop)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "musicone_ai_reply"
        private const val NOTIFICATION_ID = 3101
        internal fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AiReplyService::class.java))
        }
        internal fun stop(context: Context) { context.stopService(Intent(context, AiReplyService::class.java)) }
    }
}
