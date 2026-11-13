package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 打电话工具：调起系统拨号界面（无需拨号权限，需用户确认）；支持联系人名字 */
class CallTool : Tool {
    override val name = "make_call"
    override val description = "打电话。参数 phone = 手机号 或 联系人名字（自动从通讯录匹配）。会弹出系统拨号界面让你确认拨打。如「打电话给10086」「打电话给妈妈」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("phone", JSONObject().put("type", "string").put("description", "手机号（如 13800138000）或联系人名字（如 妈妈）"))
        )
        .put("required", org.json.JSONArray().put("phone"))

    override fun execute(args: JSONObject): String {
        val phone = args.optString("phone", "").trim()
        if (phone.isEmpty()) return "错误：缺少 phone 参数"
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
        if (!ToolRegistry.confirm("打电话", "拨打 $number ？")) {
            return "已取消拨号"
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "✅ 已打开拨号界面（$number，点拨打即可）"
        } catch (e: Exception) {
            "打开拨号界面失败: ${e.message}"
        }
    }
}