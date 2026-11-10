package com.operit.qwenapp.tools

import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 打开网页工具：用系统浏览器打开网址 */
class OpenWebsiteTool : Tool {
    override val name = "open_website"
    override val description = "用浏览器打开网页。参数 url = 网址（可省略协议，自动补 https://）。如「打开百度」→ https://www.baidu.com。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject().put("type", "string").put("description", "要打开的网址，如 www.baidu.com 或 https://example.com"))
        )
        .put("required", org.json.JSONArray().put("url"))

    override fun execute(args: JSONObject): String {
        var url = args.optString("url", "").trim()
        if (url.isEmpty()) return "错误：缺少 url 参数"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "✅ 已用浏览器打开：$url"
        } catch (e: Exception) {
            "打开网页失败（可能没有浏览器或网址非法）: ${e.message}"
        }
    }
}