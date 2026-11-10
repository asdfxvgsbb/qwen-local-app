package com.operit.qwenapp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

/** 剪贴板工具：读 / 写系统剪贴板 */
class ClipboardTool : Tool {
    override val name = "clipboard"
    override val description = "读写剪贴板。action = read（读取）或 write（写入），text = 写入的内容（write 时必填）。如「复制这个网址」→ write；「粘贴出来看看」→ read。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "read=读取剪贴板，write=写入剪贴板"))
            .put("text", JSONObject().put("type", "string").put("description", "要写入的内容，action=write 时必填"))
        )
        .put("required", org.json.JSONArray().put("action"))

    override fun execute(args: JSONObject): String {
        val action = args.optString("action", "").trim().lowercase()
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (action) {
            "read", "读取", "看看", "粘贴" -> {
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) "（剪贴板是空的）"
                else clip.getItemAt(0).coerceToText(ctx).toString()
            }
            "write", "写入", "复制" -> {
                val text = args.optString("text", "").trim()
                if (text.isEmpty()) "错误：write 需要 text 参数（要复制什么内容？）"
                else {
                    cm.setPrimaryClip(ClipData.newPlainText("qwen", text))
                    "✅ 已复制到剪贴板：$text"
                }
            }
            else -> "错误：action 只能是 read 或 write"
        }
    }
}