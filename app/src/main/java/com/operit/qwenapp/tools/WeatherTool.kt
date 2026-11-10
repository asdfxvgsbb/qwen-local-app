package com.operit.qwenapp.tools

import org.json.JSONObject
import java.net.URI

/** 天气查询：用 wttr.in（免 key） */
class WeatherTool : Tool {
    override val name = "get_weather"
    override val description = "查询某个城市的实时天气。参数 city 为城市名，如 广州。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("city", JSONObject()
            .put("type", "string")
            .put("description", "城市名，中文或拼音，如：广州、beijing")))
        .put("required", org.json.JSONArray().put("city"))

    override fun execute(args: JSONObject): String {
        val city = args.optString("city", "").trim()
        if (city.isEmpty()) return "错误：缺少 city 参数"
        return try {
            // wttr.in 返回紧凑格式，fmt=3 一行精简
            val url = URI("https://wttr.in/${java.net.URLEncoder.encode(city, "UTF-8")}?format=3&lang=zh").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            if (text.contains("Unknown location") || text.isBlank()) {
                "查询失败：未找到城市 $city 的天气"
            } else {
                text
            }
        } catch (e: Exception) {
            "查询失败：${e.message}"
        }
    }
}