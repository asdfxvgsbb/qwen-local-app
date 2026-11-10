package com.operit.qwenapp.tools

import org.json.JSONObject

/** 计算器：安全求值数学表达式 */
class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "计算数学表达式，支持 + - * / 和括号，例如 (23*17)+5。用于精确计算。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("expr", JSONObject()
            .put("type", "string")
            .put("description", "数学表达式")))
        .put("required", JSONArray_of("expr"))

    override fun execute(args: JSONObject): String {
        val expr = args.optString("expr", "").trim()
        if (expr.isEmpty()) return "错误：缺少 expr 参数"
        // 白名单校验：只允许数字、运算符、括号
        if (!expr.matches(Regex("^[0-9+\\-*/().%\\s]+$"))) {
            return "错误：表达式包含非法字符"
        }
        return try {
            val result = eval(expr)
            "$expr = $result"
        } catch (e: Exception) {
            "表达式错误: ${e.message}"
        }
    }

    /** 用 JavaScript 引擎求值（Rhino），只处理纯算术 */
    private fun eval(expr: String): Double {
        val rhino = org.mozilla.javascript.Context.enter()
        rhino.optimizationLevel = -1
        try {
            val scope = rhino.initStandardObjects()
            val result = rhino.evaluateString(scope, "($expr)", "calc", 1, null)
            return org.mozilla.javascript.Context.toNumber(result)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    private fun JSONArray_of(vararg items: String): org.json.JSONArray {
        val a = org.json.JSONArray()
        items.forEach { a.put(it) }
        return a
    }
}