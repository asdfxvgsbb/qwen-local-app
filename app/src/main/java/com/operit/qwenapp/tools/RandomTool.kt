package com.operit.qwenapp.tools

import org.json.JSONObject
import kotlin.random.Random

/** 随机工具：随机数字 / 随机选项 */
class RandomTool : Tool {
    override val name = "random"
    override val description = "生成随机内容。type = number（随机数，配合 min/max）或 choice（随机选项，配合 options）。如「1到100随机数」→ number,min=1,max=100；「帮我选一个：吃饭/睡觉/学习」→ choice,options=[吃饭,睡觉,学习]。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("type", JSONObject().put("type", "string").put("description", "number=随机数，choice=随机选项"))
            .put("min", JSONObject().put("type", "integer").put("description", "随机数下限（type=number 时）"))
            .put("max", JSONObject().put("type", "integer").put("description", "随机数上限（type=number 时）"))
            .put("options", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "选项列表（type=choice 时）"))
        )
        .put("required", org.json.JSONArray().put("type"))

    override fun execute(args: JSONObject): String {
        return when (args.optString("type", "").trim().lowercase()) {
            "number", "random", "数字", "随机数" -> {
                val min = args.optInt("min", 1)
                val max = args.optInt("max", 100)
                if (max <= min) "错误：max 必须大于 min"
                else "🎲 随机数：${Random.nextInt(min, max + 1)}（范围 $min~$max）"
            }
            "choice", "选择", "选项" -> {
                val arr = args.optJSONArray("options")
                if (arr == null || arr.length() == 0) "错误：choice 需要 options 选项列表"
                else {
                    val opts = (0 until arr.length()).map { arr.getString(it) }
                    "🎯 帮你选了：${opts[Random.nextInt(opts.size)]}"
                }
            }
            else -> "错误：type 只能是 number 或 choice"
        }
    }
}