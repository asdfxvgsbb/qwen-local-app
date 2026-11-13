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
            // 用 format=j1 拿完整 JSON，自己解析出「气温/体感/描述/湿度」，
            // 明确标注字段，避免小模型把「体感温度」当成「气温」复述（原来 fmt=3 只有一行 +11°C）
            val url = URI("https://wttr.in/${java.net.URLEncoder.encode(city, "UTF-8")}?format=j1&lang=zh").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 curl/8.0")
            val text = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            val obj = try { JSONObject(text) } catch (e: Exception) { null }
            if (obj == null || text.contains("Unknown location") || text.isBlank()) {
                "查询失败：未找到城市 $city 的天气"
            } else {
                parse(obj, city)
            }
        } catch (e: Exception) {
            "查询失败：${e.message}"
        }
    }

    /** 解析 wttr.in JSON，返回结构化中文（明确气温 vs 体感） */
    private fun parse(obj: JSONObject, city: String): String {
        // nearest_area[0].areaName[0].value —— areaName 是 [{"value":"Beijing"}] 结构
        val area = obj.optJSONArray("nearest_area")?.optJSONObject(0)
            ?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value", "")
            ?.takeIf { it.isNotBlank() } ?: city
        val cc = obj.optJSONArray("current_condition")?.optJSONObject(0)
            ?: return "查询失败：$city 天气数据为空"
        val temp = cc.optString("temp_C", "?")       // 实际气温
        val feels = cc.optString("FeelsLikeC", "?")  // 体感温度
        val desc = cc.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "") ?: ""
        val humidity = cc.optString("humidity", "?")
        val wind = cc.optString("windspeedKmph", "0").toIntOrNull()?.let { "${(it * 0.2778 * 10).roundToInt() / 10.0} m/s" } ?: "?"
        return "$area 当前天气：$desc，气温 $temp°C（体感 $feels°C），湿度 $humidity%，风速约 $wind"
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}