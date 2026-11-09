package com.operit.qwenapp.tools

import org.json.JSONObject

/**
 * 工具接口：每个工具提供给模型的定义 + 实际执行函数
 */
interface Tool {
    /** 工具名（模型调用时的函数名） */
    val name: String

    /** 工具描述 */
    val description: String

    /** 参数 JSON Schema（OpenAI 格式） */
    val parametersSchema: JSONObject

    /** 执行工具，返回结果字符串（喂回给模型） */
    fun execute(args: JSONObject): String
}