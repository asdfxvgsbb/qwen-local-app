package com.operit.qwenapp.tools

import org.json.JSONObject

/**
 * 【编程类工具】在手机本地执行代码。
 * 支持三种语言：
 *  - javascript: 用 Rhino 引擎直接跑（纯本地、无网络）
 *  - python: 尝试调用系统 python3（通常没有，会返回错误提示）
 *  - shell: 尝试执行系统命令（Android 上受限）
 */
class CodeRunnerTool : Tool {
    override val name = "run_code"
    override val description = "在本地执行一段代码。支持 javascript（推荐，可算数学、处理字符串、写小工具）。" +
        "参数：language = javascript|python|shell，code = 要执行的代码。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("language", JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray().put("javascript").put("python").put("shell"))
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

    /** Rhino 跑 JS，捕获 console.log */
    private fun runJs(code: String): String {
        return try {
            val cx = org.mozilla.javascript.Context.enter()
            cx.optimizationLevel = -1
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            try {
                val scope = cx.initStandardObjects()
                // 捕获 console.log
                val console = org.mozilla.javascript.Context.javaToJS(
                    object {
                        @Suppress("unused")
                        fun log(vararg args: Any?) {
                            sb.append(args.joinToString(" ") { it?.toString() ?: "null" }).append('\n')
                        }
                    }, scope)
                org.mozilla.javascript.ScriptableObject.putProperty(scope, "console", console)
                val result = cx.evaluateString(scope, code, "code", 1, null)
                val out = sb.toString().trim()
                if (out.isNotEmpty()) out else (result?.toString() ?: "（无输出）")
            } finally {
                org.mozilla.javascript.Context.exit()
            }
        } catch (e: Exception) {
            "JS 执行错误: ${e.message}"
        }
    }

    private val sb = StringBuilder()

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