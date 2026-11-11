package com.operit.qwenapp.tools

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

/** 音量调节工具：媒体音量加减 / 静音 */
class VolumeTool : Tool {
    override val name = "volume"
    override val description = "调节媒体音量。action = up（调大）/ down（调小）/ mute（静音）/ unmute（取消静音）。如「音量调大一点」「静音」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "up=调大，down=调小，mute=静音，unmute=取消静音"))
        )
        .put("required", org.json.JSONArray().put("action"))

    override fun execute(args: JSONObject): String {
        val action = args.optString("action", "").trim().lowercase()
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            when (action) {
                "up", "调大", "大声", "大点" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + (max / 5)).coerceAtMost(max), 0)
                    "✅ 音量调到 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$max"
                }
                "down", "调小", "小声", "小点" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (cur - (max / 5)).coerceAtLeast(0), 0)
                    "✅ 音量调到 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$max"
                }
                "mute", "静音", "无声" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    "✅ 已静音"
                }
                "unmute", "取消静音" -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 3, 0)
                    "✅ 已取消静音（音量 ${max / 3}/$max）"
                }
                else -> "错误：action 只能是 up/down/mute/unmute"
            }
        } catch (e: Exception) {
            "音量调节失败: ${e.message}"
        }
    }
}