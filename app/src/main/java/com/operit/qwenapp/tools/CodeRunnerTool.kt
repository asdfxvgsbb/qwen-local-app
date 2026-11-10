package com.operit.qwenapp.tools

import org.json.JSONObject

/**
 * 【编程类工具】在手机本地执行代码。
 * 支持：
 *  - javascript: 用 Rhino 引擎直接跑（纯本地、无网络），取表达式结果
 *  - python: 尝试调用系统 python3（Android 通常没有，返回提示）
 *  - shell: 尝试执行系统命令（Android 上受限）
 */
class CodeRunnerTool : Tool {
    override val name = "run_code"
    override val description = "在本地执行一段代码，返回执行结果。" +
        "支持 javascript（推荐，可算数学、处理字符串、写小工具）。" +
        "参数：language = javascript|python|shell，code = 要执行的代码。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("language", JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray().put("javascript").put("python").put("shell")))
            .put("code", JSONObject()
                .put("type", "string")
                .put("description", "要执行的代码")))
        .put("required", org.json.JSONArray().put("code"))

    override fun execute(args: JSONObject): String {
        val language = args.optString("language", "javascript")
        val code = args.optString("code", "")
        if (code.isBlank()) return "错误：缺少 code 参数"
        return when (language) {
            "javascript" -> runJs(code)
            "python" -> runExternal("python3", code, "py")
            "shell" -> runExternal("sh", code, "sh")
            else -> "不支持的 language: $language（支持 javascript/python/shell）"
        }
    }

    /** Rhino 跑 JS，返回最后一个表达式的结果 */
    private fun runJs(code: String): String {
        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        return try {
            val scope = cx.initStandardObjects()
            val result = cx.evaluateString(scope, code, "code", 1, null)
            result?.toString() ?: "（无输出）"
        } catch (e: Exception) {
            "JS 执行错误: ${e.message}"
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    /** 尝试调用外部解释器（Android 一般没有，给明确提示） */
    private fun runExternal(cmd: String, code: String, ext: String): String {
        return try {
            val tmp = java.io.File.createTempFile("code_", ".$ext")
            tmp.writeText(code)
            val p = ProcessBuilder(cmd, tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            tmp.delete()
            if (out.isBlank()) "(无输出，退出码 ${p.exitValue()})" else out.trim()
        } catch (e: Exception) {
            "当前 Android 环境无法运行 $cmd：${e.message}（建议用 javascript）"
        }
    }
}