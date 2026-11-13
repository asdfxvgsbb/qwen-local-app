package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

/** 屏幕亮度工具：调节系统亮度（需要"修改系统设置"授权） */
class BrightnessTool : Tool {
    override val name = "brightness"
    override val description = "调节屏幕亮度。参数 level = 0-100 的亮度值，或 action = up/down。如「调暗一点」→ 降低，「屏幕亮度调到80%」→ 80。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("level", JSONObject().put("type", "integer").put("description", "目标亮度 0-100"))
            .put("action", JSONObject().put("type", "string").put("description", "up=调亮，down=调暗"))
        )
        .put("required", org.json.JSONArray())

    override fun execute(args: JSONObject): String {
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        // 检查"修改系统设置"授权，未授权时弹确认框引导跳转系统设置页
        if (!Settings.System.canWrite(ctx)) {
            val go = ToolRegistry.confirm(
                "需要「修改系统设置」权限",
                "调节屏幕亮度需要系统授权。\n\n点击「去授权」将跳转到系统设置页，请找到「本地 Agent」并打开「修改系统设置」开关，然后返回重试。"
            )
            if (go) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return "已为你打开系统设置页，请开启「修改系统设置」权限后，再叫我调亮度"
                } catch (e: Exception) {
                    return "无法打开设置页：${e.message}。请手动到 系统设置→应用→本地 Agent→权限→修改系统设置 开启"
                }
            }
            return "已取消（未授权时无法调节亮度，可到设置页手动开启）"
        }
        return try {
            val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) // 0-255
            val target: Int
            val action = args.optString("action", "").lowercase()
            if (args.has("level")) {
                target = (args.optInt("level", 50).coerceIn(0, 100) * 255 / 100)
            } else if (action.contains("up") || action == "调亮" || action == "亮一点" || action == "亮点") {
                target = (cur + 51).coerceAtMost(255)
            } else if (action.contains("down") || action == "调暗" || action == "暗一点" || action == "暗点") {
                target = (cur - 51).coerceAtLeast(1)
            } else {
                return "错误：需要 level 或 action 参数"
            }
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            "✅ 屏幕亮度已调到 ${target * 100 / 255}%"
        } catch (e: Exception) {
            "亮度调节失败: ${e.message}"
        }
    }
}