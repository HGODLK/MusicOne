package com.musicone.demo

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val HYPER_OS_PROBE_ID = 1704
private const val HYPER_OS_PROBE_TAG = "musicone_island_probe"

/** 只由用户点击触发的普通通知对照，不经过播放器或媒体通知装饰器。 */
@RequiresApi(26)
internal object HyperOsIslandProbe {
    suspend fun send(context: Context): String = withContext(Dispatchers.IO) {
        val permission = focusPermission(context)
        if (!HyperOsIslandAccess.notificationsAllowed(context)) {
            return@withContext "$permission\n普通通知权限未开启，未发送测试通知。"
        }
        try {
            ensureHyperOsIslandChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(HYPER_OS_PROBE_TAG, HYPER_OS_PROBE_ID, notification(context))
            "$permission\n已发送独立测试通知，请回桌面查看“岛测试”；一分钟后自动清除。权限允许不代表通过超级岛准入认证。"
        } catch (error: RuntimeException) {
            "$permission\n测试通知发送失败：${error.javaClass.simpleName}"
        }
    }

    fun clear(context: Context): String = try {
        context.getSystemService(NotificationManager::class.java).cancel(HYPER_OS_PROBE_TAG, HYPER_OS_PROBE_ID)
        "独立测试通知已清除。"
    } catch (error: RuntimeException) {
        "清除失败：${error.javaClass.simpleName}"
    }

    private fun focusPermission(context: Context): String = try {
        val result = context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus", null, Bundle().apply { putString("package", context.packageName) })
        when {
            result == null || !result.containsKey("canShowFocus") -> "焦点权限查询：系统未返回结果"
            result.getBoolean("canShowFocus", false) -> "焦点权限查询：允许（canShowFocus=true）"
            else -> "焦点权限查询：不允许（canShowFocus=false）"
        }
    } catch (error: RuntimeException) {
        "焦点权限查询：不可用（${error.javaClass.simpleName}）"
    }

    internal fun notification(context: Context): Notification {
        // 从空白 Builder 构建，不能复制媒体通知的样式、会话或 RemoteViews。
        fun picture() = JSONObject().put("type", 1).put("pic", HYPER_OS_COVER_KEY)
        val parameters = JSONObject()
            .put("protocol", 1).put("business", "lyric_display").put("updatable", true)
            .put("reopen", "reopen").put("timeout", 1).put("islandFirstFloat", false)
            .put("enableFloat", false).put("filterWhenNoPermission", false)
            .put("ticker", "岛测试").put("aodTitle", "超级岛测试")
            .put("baseInfo", JSONObject().put("type", 2).put("title", "超级岛测试")
                .put("content", "普通通知，无媒体样式"))
            .put("hintInfo", JSONObject().put("type", 1).put("title", "测试"))
            .put("param_island", JSONObject().put("islandProperty", 1).put("islandOrder", true)
                .put("islandTimeout", 60)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", picture())
                        .put("textInfo", JSONObject().put("title", "岛测试")))
                    .put("textInfo", JSONObject().put("title", "测试")))
                .put("smallIslandArea", JSONObject().put("picInfo", picture())))
        return Notification.Builder(context, HYPER_OS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_album_placeholder)
            .setContentTitle("超级岛独立测试").setContentText("普通通知，无媒体样式；一分钟后自动清除")
            .setTimeoutAfter(60_000L).setOnlyAlertOnce(true)
            .addExtras(Bundle().apply {
                putString(HYPER_OS_STANDARD_PARAM, JSONObject().put("param_v2", parameters).toString())
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable(HYPER_OS_COVER_KEY, Icon.createWithResource(context, R.drawable.ic_island_album_placeholder))
                })
            }).build()
    }
}
