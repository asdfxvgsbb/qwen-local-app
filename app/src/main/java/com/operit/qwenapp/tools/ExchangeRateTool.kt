package com.operit.qwenapp.tools

import org.json.JSONObject
import java.net.URLEncoder

/** 汇率换算工具：实时外汇汇率（免费API，无需key） */
class ExchangeRateTool : Tool {
    override val name = "exchange_rate"
    override val description = "实时汇率换算。参数 amount = 金额（默认1），from = 源货币代码（如 USD/CNY/EUR/JPY/HKD），to = 目标货币代码。如「1美元等于多少人民币」→ amount=1,from=USD,to=CNY。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("amount", JSONObject().put("type", "number").put("description", "金额，默认1"))
            .put("from", JSONObject().put("type", "string").put("description", "源货币代码，如 USD/CNY/EUR/JPY/HKD/GBP"))
            .put("to", JSONObject().put("type", "string").put("description", "目标货币代码，如 USD/CNY/EUR/JPY/HKD/GBP"))
        )
        .put("required", org.json.JSONArray().put("from").put("to"))

    override fun execute(args: JSONObject): String {
        val from = args.optString("from", "").trim().uppercase()
        val to = args.optString("to", "").trim().uppercase()
        val amount = args.optDouble("amount", 1.0)
        if (from.isEmpty() || to.isEmpty()) return "错误：需要 from 和 to 货币代码"
        if (amount <= 0) return "错误：amount 必须大于 0"

        // 主 API：open.er-api.com（免费无key）
        val rates = tryFetch("https://open.er-api.com/v6/latest/$from")
            ?: tryFetch("https://api.frankfurter.app/latest?from=$from")
            ?: return "汇率查询失败（网络不通或货币代码错误，支持 USD/CNY/EUR/JPY/HKD/GBP 等）"
        val rate = rates.optDouble(to, Double.NaN)
        if (rate.isNaN() || rate <= 0) return "不支持货币 $to（可用：${rates.keys().asSequence().toList().take(20).joinToString("/")}）"
        val result = amount * rate
        val fmt = if (result >= 1000) "%.0f".format(result) else "%.2f".format(result)
        val fmtRate = "%.4f".format(rate)
        return "💱 $amount $from = $fmt $to（汇率 1 $from = $fmtRate $to）"
    }

    /** 抓取汇率 JSON，返回 货币->汇率 map；失败返回 null */
    private fun tryFetch(url: String): JSONObject? {
        return try {
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(text)
            when {
                json.has("rates") -> json.getJSONObject("rates") // er-api / frankfurter 结构
                else -> null
            }
        } catch (e: Exception) { null }
    }
}