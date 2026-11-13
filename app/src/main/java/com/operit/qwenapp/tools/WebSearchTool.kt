package com.operit.qwenapp.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.operit.qwenapp.SettingsActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网络搜索工具：
 * 1. 若输入是网址（http/https 开头）→ 直接抓取该网页正文，不拿去搜索
 * 2. 否则用 cn.bing（国内可达）搜索前几条结果
 * 3. 不再跳维基百科兜底（部分网络连不上 zh.wikipedia.org）
 * 4. 需在设置里开启「网络搜索」开关 + 先检测联网
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "联网搜索或抓取网页。输入问题会搜索网页，输入网址会直接抓取该网页内容。参数 query = 问题或网址。如「搜索 2024年诺贝尔奖得主」或「看看 https://example.com」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "要搜索的问题，或要抓取的网址"))
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
            return "当前没有网络连接，无法联网（请检查 Wi-Fi / 移动数据）"
        }
        // 3. 输入是网址 → 直接抓正文
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return fetchUrl(query)
        }
        // 4. 否则走必应搜索
        return try {
            searchBing(query)
        } catch (e: Exception) {
            "搜索失败：${e.message}（网络可能不稳定，或 cn.bing 暂时不可达）"
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

    /** 直接抓取网页正文（去掉标签、脚本、样式） */
    private fun fetchUrl(rawUrl: String): String {
        return try {
            val conn = URL(rawUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0 Mobile Safari/537.36")
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val code = conn.responseCode
            if (code !in 200..399) return "网页返回错误状态码：$code"
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val text = stripHtml(html)
            if (text.isBlank()) "网页没有可读内容（可能是视频/图片/JS渲染页，或需要登录）"
            else {
                // 保留标题，正文截断到 1200 字
                val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                val sb = StringBuilder()
                if (!title.isNullOrEmpty()) sb.append("📄 ").append(title).append('\n')
                sb.append(text.take(1200))
                if (text.length > 1200) sb.append("\n…（内容较长已截断）")
                sb.toString()
            }
        } catch (e: Exception) {
            "抓取网页失败：${e.message}"
        }
    }

    /** 用 cn.bing 搜索页抓前 5 条结果；失败时自动切百度备用源 */
    private fun searchBing(query: String): String {
        return try {
            searchBingImpl(query)
        } catch (e: Exception) {
            // Bing 源失败（可能被反爬/超时）→ 自动切百度
            try {
                searchBaiduImpl(query)
            } catch (e2: Exception) {
                "搜索失败：${e.message}；百度备用源也失败：${e2.message}"
            }
        }
    }

    private fun searchBingImpl(query: String): String {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val liRegex = Regex("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a></h2>[\\s\\S]*?(?:<p[^>]*>([\\s\\S]*?)</p>)?")
        val matches = liRegex.findAll(html).take(5).toList()
        if (matches.isEmpty()) return "没有搜到「$query」的结果，换个说法试试"

        val sb = StringBuilder("「$query」搜索结果：\n")
        var i = 0
        for (m in matches) {
            i++
            val link = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val snippet = m.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
            if (title.isEmpty()) continue
            sb.append("$i. ").append(title).append('\n')
            if (snippet.isNotEmpty()) sb.append(snippet).append('\n')
            sb.append("（").append(link).append("）\n")
        }
        return sb.toString().trim()
    }

    /** 百度备用搜索源 */
    private fun searchBaiduImpl(query: String): String {
        val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8") + "&ie=utf-8"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        // 百度结果容器：<div class="result c-container" ...><h3 ...><a href="...">标题</a></h3>...摘要
        val liRegex = Regex("<div[^>]*class=\"[^\"]*result[^\"]*c-container[^\"]*\"[\\s\\S]*?<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>\\s*</h3>[\\s\\S]*?(?:<span[^>]*class=\"[^\"]*content-right[^\"]*\"[^>]*>([\\s\\S]*?)</span>)?")
        val matches = liRegex.findAll(html).take(5).toList()
        if (matches.isEmpty()) return "「$query」百度没搜到结果（可能是反爬），换个说法试试"

        val sb = StringBuilder("「$query」搜索结果：\n")
        var i = 0
        for (m in matches) {
            i++
            val link = m.groupValues[1]
            var title = m.groupValues[2].replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
            val snippet = m.groupValues[3].replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) continue
            sb.append("$i. ").append(title).append('\n')
            if (snippet.isNotEmpty()) sb.append(snippet).append('\n')
            sb.append("（").append(link).append("）\n")
        }
        return sb.toString().trim()
    }

    private fun stripHtml(html: String): String {
        var s = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&middot;", "·")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}