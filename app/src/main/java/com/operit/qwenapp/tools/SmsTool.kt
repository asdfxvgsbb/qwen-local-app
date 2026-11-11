package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 发短信工具：调起系统短信界面并填入内容（无需短信权限，需用户确认发送） */
class SmsTool : Tool {
    override val name = "send_sms"
    override val description = "发短信。参数 phone = 手机号，message = 内容。会弹出系统短信界面让你确认发送。如「发短信给10086：查话费」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("phone", JSONObject().put("type", "string").put("description", "手机号，如 10086"))
            .put("message", JSONObject().put("type", "string").put("description", "短信内容"))
        )
        .put("required", org.json.JSONArray().put("phone").put("message"))

    override fun execute(args: JSONObject): String {
        val phone = args.optString("phone", "").trim()
        val message = args.optString("message", "").trim()
        if (phone.isEmpty()) return "错误：缺少 phone 参数"
        if (message.isEmpty()) return "错误：缺少 message 参数"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"

        // 确认弹窗
        if (!ToolRegistry.confirm("发短信", "发送短信到 $phone：\n\n$message\n\n确定发送吗？")) {
            return "已取消发送"
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "✅ 已打开短信界面（填写给 $phone，点发送即可）"
        } catch (e: Exception) {
            "打开短信界面失败: ${e.message}"
        }
    }
}