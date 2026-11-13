package com.operit.qwenapp.tools

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮窗管理器（v9.21）：
 * - 顶部半透明长条，实时显示最近一次工具调用的内容/结果。
 * - 截图前调用 hideForScreenshot()：隐藏长条，改在右上角显示一个小亮点（避免截到长条）；
 *   截图后 restoreAfterScreenshot()：移除亮点、恢复长条。
 * - 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），未授权时静默不显示。
 */
object FloatWindowManager {

    @Volatile private var bar: TextView? = null
    @Volatile private var dot: View? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    /** 显示/更新长条（调用工具时传内容） */
    fun show(ctx: Context, text: String) {
        if (!hasOverlayPermission(ctx)) return
        main.post {
            try {
                ensure(ctx)
                val b = bar ?: return@post
                b.text = text.take(80) + if (text.length > 80) "…" else ""
                if (!b.isAttachedToWindow) {
                    wm?.addView(b, barParams())
                }
            } catch (_: Exception) {}
        }
    }

    /** 截图前：隐藏长条，显示右上角小亮点 */
    fun hideForScreenshot(ctx: Context) {
        if (!hasOverlayPermission(ctx)) return
        main.post {
            try {
                ensure(ctx)
                bar?.let { if (it.isAttachedToWindow) wm?.removeView(it) }
                if (dot == null) {
                    dot = View(ctx).apply { setBackgroundColor(0xFFFFD54F.toInt()) } // 亮黄色小圆
                }
                dot?.let { if (!it.isAttachedToWindow) wm?.addView(it, dotParams()) }
            } catch (_: Exception) {}
        }
    }

    /** 截图后：移除亮点，恢复长条（保持上次内容） */
    fun restoreAfterScreenshot(ctx: Context) {
        if (!hasOverlayPermission(ctx)) return
        main.post {
            try {
                ensure(ctx)
                dot?.let { if (it.isAttachedToWindow) wm?.removeView(it) }
                bar?.let { if (it.isAttachedToWindow != true) wm?.addView(it, barParams()) }
            } catch (_: Exception) {}
        }
    }

    /** 彻底关闭（不用了） */
    fun dismiss(ctx: Context) {
        main.post {
            try {
                bar?.let { if (it.isAttachedToWindow) wm?.removeView(it) }
                dot?.let { if (it.isAttachedToWindow) wm?.removeView(it) }
                bar = null; dot = null
            } catch (_: Exception) {}
        }
    }

    fun hasOverlayPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    private fun ensure(ctx: Context) {
        if (wm == null) wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (bar == null) {
            bar = TextView(ctx).apply {
                setBackgroundColor(0xCC111111.toInt())
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(24, 14, 24, 14)
                gravity = Gravity.CENTER
                alpha = 0.93f
                text = "本地 Agent"
            }
        }
    }

    private fun ovType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun barParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        ovType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 100
    }

    private fun dotParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        40, 40,
        ovType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 24
        y = 24
    }
}