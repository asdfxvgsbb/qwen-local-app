package com.operit.qwenapp.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.operit.qwenapp.MainActivity
import org.json.JSONObject

/** 闹钟提醒工具：解析"X分钟后/秒后/小时后提醒我…"，到点发通知 */
class SetReminderTool : Tool {
    override val name = "set_reminder"
    override val description = "设置闹钟提醒/倒计时提醒。参数 delay_seconds = 秒数后提醒，message = 提醒内容。如「10分钟后提醒我喝水」→ delay_seconds=600, message=喝水。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("delay_seconds", JSONObject().put("type", "integer").put("description", "多少秒后提醒，如 600 = 10分钟后"))
            .put("message", JSONObject().put("type", "string").put("description", "提醒内容，如 喝水"))
        )
        .put("required", org.json.JSONArray().put("delay_seconds").put("message"))

    override fun execute(args: JSONObject): String {
        val delaySec = args.optLong("delay_seconds", -1)
        val msg = args.optString("message", "").trim()
        if (delaySec <= 0) return "错误：delay_seconds 必须是大于0的秒数"
        if (msg.isEmpty()) return "错误：缺少 message 提醒内容"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"

        // Android 13+ (API 33) 通知权限：未授权时引导
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val go = ToolRegistry.confirm(
                "需要通知权限",
                "提醒到点后需要发送通知。\n\n点击「去授权」将跳转到系统设置开启通知权限，然后返回重试。"
            )
            if (go) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return "已为你打开通知设置，请开启「本地 Agent」通知权限后再设置提醒"
                } catch (e: Exception) {
                    return "无法打开通知设置：${e.message}。请到 系统设置→应用→本地 Agent→通知 开启"
                }
            }
            return "已取消（未授权时无法发送提醒通知）"
        }

        // 通知渠道（Android 8+ 必须）
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("reminder", "提醒", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)

        // 点击通知打开 App
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Handler(Looper.getMainLooper()).postDelayed({
            val notif = NotificationCompat.Builder(ctx, "reminder")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("⏰ 提醒")
                .setContentText(msg)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
            } catch (e: Exception) {
                // 通知失败不影响主流程
            }
        }, delaySec * 1000L)

        val whenStr = if (delaySec < 60) "${delaySec}秒" else if (delaySec < 3600) "${delaySec / 60}分钟" else "${delaySec / 3600}小时${(delaySec % 3600) / 60}分钟"
        return "✅ 已设置提醒：${whenStr}后提醒「$msg」"
    }
}