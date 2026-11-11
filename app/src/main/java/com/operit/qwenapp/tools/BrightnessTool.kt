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
        // 检查"修改系统设置"授权
        if (!Settings.System.canWrite(ctx)) {
            return "需要「修改系统设置」权限：请到 系统设置→应用→本地Agent→权限→修改系统设置 打开，或在设置页手动授权后再试"
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