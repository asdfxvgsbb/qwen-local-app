package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 发短信工具：调起系统短信界面并填入内容（无需短信权限）；支持联系人名字 */
class SmsTool : Tool {
    override val name = "send_sms"
    override val description = "发短信。参数 phone = 手机号或联系人名字（自动匹配），message = 内容。会弹出系统短信界面让你确认发送。如「发短信给10086：查话费」「发短信给妈妈：晚上回家吃饭」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("phone", JSONObject().put("type", "string").put("description", "手机号或联系人名字，如 10086 / 妈妈"))
            .put("message", JSONObject().put("type", "string").put("description", "短信内容"))
        )
        .put("required", org.json.JSONArray().put("phone").put("message"))

    override fun execute(args: JSONObject): String {
        val phone = args.optString("phone", "").trim()
        val message = args.optString("message", "").trim()
        if (phone.isEmpty()) return "错误：缺少 phone 参数"
        if (message.isEmpty()) return "错误：缺少 message 参数"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"

        // 如果参数不是纯号码，尝试从通讯录解析
        var number = phone
        if (!phone.matches(Regex("^\\d[\\d\\s\\-()*#+]{2,20}$"))) {
            val resolved = ContactsHelper.resolveNameToNumber(ctx, phone)
            if (resolved != null) {
                number = resolved
            } else if (!ContactsHelper.hasPermission(ctx)) {
                return ContactsHelper.guideToSettings(ctx)
            } else {
                return "通讯录里没找到「$phone」，你可以直接告诉我号码（如 13800138000）"
            }
        }

        // 确认弹窗（显示解析后的号码）
        if (!ToolRegistry.confirm("发短信", "发送短信到 $number：\n\n$message\n\n确定发送吗？")) {
            return "已取消发送"
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "✅ 已打开短信界面（填写给 $number，点发送即可）"
        } catch (e: Exception) {
            "打开短信界面失败: ${e.message}"
        }
    }
}