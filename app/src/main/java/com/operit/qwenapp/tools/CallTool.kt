package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 打电话工具：调起系统拨号界面（无需拨号权限，需用户确认） */
class CallTool : Tool {
    override val name = "make_call"
    override val description = "打电话。参数 phone = 手机号。会弹出系统拨号界面让你确认拨打。如「打电话给10086」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("phone", JSONObject().put("type", "string").put("description", "手机号，如 10086 或 13800138000"))
        )
        .put("required", org.json.JSONArray().put("phone"))

    override fun execute(args: JSONObject): String {
        val phone = args.optString("phone", "").trim()
        if (phone.isEmpty()) return "错误：缺少 phone 参数"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"

        // 确认弹窗
        if (!ToolRegistry.confirm("打电话", "拨打 $phone ？")) {
            return "已取消拨号"
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "✅ 已打开拨号界面（$phone，点拨打即可）"
        } catch (e: Exception) {
            "打开拨号界面失败: ${e.message}"
        }
    }
}