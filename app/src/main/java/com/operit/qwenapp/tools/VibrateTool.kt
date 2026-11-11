package com.operit.qwenapp.tools

import android.content.Context
import android.os.Vibrator
import org.json.JSONObject

/** 震动工具：手机震动一下 */
class VibrateTool : Tool {
    override val name = "vibrate"
    override val description = "让手机震动。参数 duration_ms = 震动时长毫秒，默认 300。如「震动一下」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("duration_ms", JSONObject().put("type", "integer").put("description", "震动时长毫秒，默认 300"))
        )
        .put("required", org.json.JSONArray())

    override fun execute(args: JSONObject): String {
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        return try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val ms = args.optInt("duration_ms", 300).coerceIn(50, 5000)
            if (v.hasVibrator()) {
                v.vibrate(ms.toLong())
                "✅ 已震动（${ms}ms）"
            } else "（此设备没有震动马达）"
        } catch (e: Exception) {
            "震动失败: ${e.message}"
        }
    }
}