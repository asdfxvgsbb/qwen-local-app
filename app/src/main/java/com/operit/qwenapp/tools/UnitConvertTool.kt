package com.operit.qwenapp.tools

import org.json.JSONObject
import kotlin.math.pow

/**
 * 【单位换算】纯本地计算，零延迟。
 * 支持：长度(m/km/cm/mm)、重量(g/kg/t)、温度(C/F)、数据(KB/MB/GB)。
 */
class UnitConvertTool : Tool {
    override val name = "unit_convert"
    override val description = "单位换算。参数 category = length|weight|temperature|data，" +
        "value = 数值，from = 原单位，to = 目标单位。" +
        "长度单位 m/km/cm/mm，重量 g/kg/t，温度 c/f，数据 kb/mb/gb。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("category", JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray()
                    .put("length").put("weight").put("temperature").put("data"))
                .put("description", "换算类别"))
            .put("value", JSONObject()
                .put("type", "number")
                .put("description", "数值"))
            .put("from", JSONObject()
                .put("type", "string")
                .put("description", "原单位"))
            .put("to", JSONObject()
                .put("type", "string")
                .put("description", "目标单位")))
        .put("required", org.json.JSONArray()
            .put("category").put("value").put("from").put("to"))

    override fun execute(args: JSONObject): String {
        val category = args.optString("category", "")
        val value = args.optDouble("value", Double.NaN)
        val from = args.optString("from", "").lowercase()
        val to = args.optString("to", "").lowercase()
        if (value.isNaN()) return "错误：缺少有效的 value 数值"
        return try {
            when (category) {
                "length" -> convert(value, from, to, mapOf(
                    "km" to 1000.0, "m" to 1.0, "cm" to 0.01, "mm" to 0.001))
                "weight" -> convert(value, from, to, mapOf(
                    "t" to 1000.0, "kg" to 1.0, "g" to 0.001))
                "data" -> convert(value, from, to, mapOf(
                    "gb" to 1024.0, "mb" to 1.0, "kb" to 1.0/1024))
                "temperature" -> convertTemp(value, from, to)
                else -> "不支持的类别: $category（支持 length/weight/temperature/data）"
            }
        } catch (e: Exception) {
            "换算错误: ${e.message}"
        }
    }

    /** 线性单位换算 */
    private fun convert(value: Double, from: String, to: String, table: Map<String, Double>): String {
        val f = table[from] ?: return "未知单位: $from（支持 ${table.keys}）"
        val t = table[to] ?: return "未知单位: $to（支持 ${table.keys}）"
        val result = value * f / t
        return "$value $from = ${String.format("%.6g", result)} $to"
    }

    /** 温度换算 */
    private fun convertTemp(value: Double, from: String, to: String): String {
        val celsius = when (from) {
            "c", "celsius" -> value
            "f", "fahrenheit" -> (value - 32) * 5 / 9
            else -> return "未知温度单位: $from（支持 c/f）"
        }
        val result = when (to) {
            "c", "celsius" -> celsius
            "f", "fahrenheit" -> celsius * 9 / 5 + 32
            else -> return "未知温度单位: $to（支持 c/f）"
        }
        return "$value $from = ${String.format("%.2f", result)} $to"
    }
}