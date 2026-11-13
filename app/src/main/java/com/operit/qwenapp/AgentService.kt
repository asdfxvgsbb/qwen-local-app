package com.operit.qwenapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 前台保活服务（v9.22）：
 * 常驻通知「本地 Agent 运行中」，提高进程优先级，App 退后台后不被系统回收，
 * 从而保住 llama-server 子进程、悬浮窗与工具链路。
 * - START_STICKY：被系统杀掉后会自动重启
 * - 通知 IMPORTANCE_LOW：不打扰用户
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_keepalive"
        const val NOTIFICATION_ID = 1001

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, AgentService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, AgentService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "本地 Agent", NotificationManager.IMPORTANCE_LOW)
        channel.description = "保持本地模型与工具服务在后台运行"
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("本地 Agent 运行中")
            .setContentText("本地模型与手机操控服务正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}