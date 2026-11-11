package com.operit.qwenapp.tools

import org.json.JSONObject
import java.net.URLEncoder

/** 网页摘要工具：抓取网页正文简介（不需要打开浏览器） */
class WebSummaryTool : Tool {
    override val name = "web_summary"
    override val description = "抓取一个网页的正文摘要。参数 url = 网址。如「看看这个网页讲什么 https://xxx.com」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject().put("type", "string").put("description", "要抓取的网址，如 https://example.com"))
        )
        .put("required", org.json.JSONArray().put("url"))

    override fun execute(args: JSONObject): String {
        var url = args.optString("url", "").trim()
        if (url.isEmpty()) return "错误：缺少 url 参数"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return try {
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
            conn.instanceFollowRedirects = true
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val text = stripHtml(html)
            if (text.isBlank()) "网页没有可读内容（可能是视频/图片页或需要登录）"
            else text.take(1000) + (if (text.length > 1000) "\n…（已截断）" else "")
        } catch (e: Exception) {
            "抓取失败: ${e.message}"
        }
    }

    private fun stripHtml(html: String): String {
        // 去掉 script/style
        var s = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        // 去标签
        s = s.replace(Regex("<[^>]+>"), " ")
        // 反转义
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
        // 压缩空白
        return s.replace(Regex("\\s+"), " ").trim()
    }
}