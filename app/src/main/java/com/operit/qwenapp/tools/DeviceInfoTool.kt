package com.operit.qwenapp.tools

import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject

/**
 * 【设备信息】查询手机电量/存储/内存（本地毫秒级，无网络）
 */
class DeviceInfoTool : Tool {
    override val name = "device_info"
    override val description = "查询手机设备信息：电池电量、剩余存储、内存。" +
        "参数 info = battery|storage|memory|all（默认 all）。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("info", JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray()
                    .put("battery").put("storage").put("memory").put("all"))
                .put("description", "要查询的信息类型")))
        .put("required", org.json.JSONArray())

    override fun execute(args: JSONObject): String {
        val info = args.optString("info", "all")
        val parts = mutableListOf<String>()
        if (info == "all" || info == "battery") parts.add(battery())
        if (info == "all" || info == "storage") parts.add(storage())
        if (info == "all" || info == "memory") parts.add(memory())
        return parts.joinToString("\n")
    }

    private fun battery(): String {
        return try {
            val ctx = ToolRegistry.appContext
                ?: return "🔋 电池: 无可用的应用上下文"
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "🔋 电池电量: ${level}%"
        } catch (e: Exception) {
            "🔋 电池: 无法读取"
        }
    }

    private fun storage(): String {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val gb = 1024f * 1024f * 1024f
            "💾 存储: 剩余 ${String.format("%.1f", free/gb)}GB / 共 ${String.format("%.1f", total/gb)}GB"
        } catch (e: Exception) {
            "💾 存储: 无法读取"
        }
    }

    private fun memory(): String {
        return try {
            val rm = java.lang.Runtime.getRuntime()
            val total = rm.totalMemory() / 1024 / 1024
            val free = rm.freeMemory() / 1024 / 1024
            "🧠 内存: App可用 ${String.format("%.0f", free/1024f)}GB / ${String.format("%.0f", total/1024f)}GB"
        } catch (e: Exception) {
            "🧠 内存: 无法读取"
        }
    }
}