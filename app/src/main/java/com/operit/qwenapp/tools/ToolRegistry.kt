package com.operit.qwenapp.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册表：集中管理所有工具，生成 OpenAI tools 数组，按名执行
 */
object ToolRegistry {

    /** 由 MainActivity 注入的 Application Context（工具内需要环境时使用） */
    @Volatile
    var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private val tools: List<Tool> = listOf(
        CalculatorTool(),
        WeatherTool(),
        GetTimeTool(),
        CodeRunnerTool(),   // 【编程类工具】运行 Python / JS / Shell 代码
        FileTool(),
        TerminalTool(),     // 【内置终端】直接执行 shell 命令
        DeviceInfoTool(),   // 【设备信息】电量/存储/内存
        UnitConvertTool(),  // 【单位换算】长度/重量/温度/数据
        MemorizeTool()      // 【记忆】记住用户偏好/备忘
    )

    /** 生成传给 /v1/chat/completions 的 tools 参数（names 为 null 时传全部） */
    fun toToolsJson(names: List<String>? = null): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            if (names != null && t.name !in names) continue
            val fn = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", t.parametersSchema)
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    /** 按名执行工具，返回结果字符串；未找到返回错误 */
    fun execute(name: String, arguments: String): String {
        val tool = tools.find { it.name == name }
            ?: return "错误：未知工具 $name"
        return try {
            val args = if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
            tool.execute(args)
        } catch (e: Exception) {
            "工具执行出错: ${e.message}"
        }
    }
}