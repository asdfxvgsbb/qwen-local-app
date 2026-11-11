package com.operit.qwenapp.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.operit.qwenapp.SettingsActivity
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网络搜索工具：在设置里可开关（KEY_WEB_SEARCH），执行前先检测联网状态。
 * 主用 cn.bing 搜索页解析前几条结果，失败降级中文维基百科摘要。
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "联网搜索网页信息。参数 query = 要搜索的问题或关键词。搜索前会自动检测网络，且需在设置里开启「网络搜索」开关。如「搜索 2024年诺贝尔奖得主」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "要搜索的问题，如 2024年诺贝尔奖得主"))
        )
        .put("required", org.json.JSONArray().put("query"))

    override fun execute(args: JSONObject): String {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return "错误：缺少 query 参数"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"

        // 1. 设置开关检查
        if (!SettingsActivity.getWebSearchEnabled(ctx)) {
            return "网络搜索未开启：请到 App 设置页打开「网络搜索」开关后重试"
        }
        // 2. 联网检测
        if (!hasNetwork(ctx)) {
            return "当前没有网络连接，无法搜索（请检查 Wi-Fi / 移动数据）"
        }
        // 3. 执行搜索：bing 优先，维基兜底
        return try {
            searchBing(query) ?: searchWiki(query)
        } catch (e: Exception) {
            try { searchWiki(query) } catch (e2: Exception) { "搜索失败: ${e2.message}" }
        }
    }

    private fun hasNetwork(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }

    /** 用 cn.bing 搜索页抓前 5 条结果（标题+网址+摘要） */
    private fun searchBing(query: String): String? {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        // 提取 <li class="b_algo">...</li> 块
        val liRegex = Regex("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a></h2>[\\s\\S]*?(?:<p[^>]*>([\\s\\S]*?)</p>)?")
        val matches = liRegex.findAll(html).take(5).toList()
        if (matches.isEmpty()) return null

        val sb = StringBuilder("「$query」搜索结果：\n")
        var i = 0
        for (m in matches) {
            i++
            val link = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val snippet = m.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
            if (title.isEmpty()) continue
            sb.append("$i. $title\n")
            if (snippet.isNotEmpty()) sb.append(snippet).append('\n')
            // 只保留域名做简短来源
            val domain = runCatching { java.net.URI(link).host?.removePrefix("www.") ?: "" }.getOrDefault("")
            if (domain.isNotEmpty()) sb.append("（$domain）\n")
        }
        return sb.toString().trim()
    }

    /** 中文维基百科摘要兜底（适合"XX是什么"类查询） */
    private fun searchWiki(query: String): String {
        val url = "https://zh.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=" +
            URLEncoder.encode(query, "UTF-8")
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(text)
        val pages = json.getJSONObject("query").getJSONObject("pages")
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.getJSONObject(keys.next())
            if (page.has("extract")) {
                val extract = page.getString("extract").trim()
                if (extract.isNotEmpty()) {
                    return "「$query」百科摘要：\n" + extract.take(800) + (if (extract.length > 800) "…" else "")
                }
            }
        }
        return "没有找到「$query」的相关结果，换个说法试试"
    }
}