package com.operit.qwenapp.tools

import org.json.JSONObject

/** 查联系人工具：按名字/关键词查通讯录，返回号码列表（只查不拨号不发短信） */
class LookupContactTool : Tool {
    override val name = "lookup_contact"
    override val description = "查询通讯录联系人。参数 name 为联系人名字或关键词（如 妈妈、张伟、王）。返回匹配的联系人及电话号码列表，不会拨号或发短信。如「查一下张伟的电话」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "联系人名字或关键词，如 妈妈、张伟、李"))
        )
        .put("required", org.json.JSONArray().put("name"))

    override fun execute(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return "错误：缺少 name 参数（要查谁的电话？）"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        if (!ContactsHelper.hasPermission(ctx)) {
            return ContactsHelper.guideToSettings(ctx)
        }
        val list = ContactsHelper.searchContacts(ctx, name)
        if (list.isEmpty()) {
            // 精确匹配再试一次（单联系人）
            val num = ContactsHelper.resolveNameToNumber(ctx, name)
            if (num != null) return "找到「$name」：$num"
            return "通讯录里没找到「$name」，请确认联系人名字"
        }
        val sb = StringBuilder("找到 ${list.size} 个联系人：")
        list.forEachIndexed { i, (n, num) ->
            sb.append("\n").append(i + 1).append(". ").append(n).append("：").append(num)
        }
        return sb.toString()
    }
}