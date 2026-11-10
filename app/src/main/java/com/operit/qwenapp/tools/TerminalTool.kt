package com.operit.qwenapp.tools

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 【内置终端】在手机本地执行 shell 命令。
 * 使用 sh -c 执行，适合 ls/cat/echo/date/pwd 等系统命令，
 * 与 run_code 的区别：直接跑命令字符串（终端式），不是跑代码段。
 */
class TerminalTool : Tool {
    override val name = "run_terminal"
    override val description = "内置终端：执行 shell 命令。参数 cmd = 要执行的命令，" +
        "如 ls /sdcard、cat 文件路径、echo hello、date、pwd。" +
        "适合查看文件/系统信息/简单操作。与 run_code 不同，这是直接执行命令。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("cmd", JSONObject()
                .put("type", "string")
                .put("description", "要执行的 shell 命令，如 ls /sdcard/ai"))
            .put("timeout", JSONObject()
                .put("type", "integer")
                .put("description", "超时秒数，默认 10")))
        .put("required", org.json.JSONArray().put("cmd"))

    override fun execute(args: JSONObject): String {
        val cmd = args.optString("cmd", "").trim()
        if (cmd.isEmpty()) return "错误：缺少 cmd 参数"
        if (cmd.length > 500) return "错误：命令过长（最多500字符）"
        val timeoutSec = args.optInt("timeout", 10).coerceIn(1, 30)
        return runCmd(cmd, timeoutSec)
    }

    private fun runCmd(cmd: String, timeoutSec: Int): String {
        // 安全校验：拦截明显危险命令
        val lower = cmd.lowercase()
        val blocked = listOf(
            "rm -rf /", "rm -rf /*", "mkfs", ">/dev/sd", "dd if=",
            "shutdown", "reboot", "format", ":(){ :|:& };:"
        )
        if (blocked.any { lower.contains(it) }) {
            return "已阻止危险命令"
        }
        return try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!ok) {
                p.destroy()
                "命令超时（${timeoutSec}s），已终止"
            } else {
                val code = p.exitValue()
                val text = out.trim()
                when {
                    text.isBlank() -> "（无输出，退出码 $code）"
                    else -> "$text\n（退出码 $code）"
                }
            }
        } catch (e: Exception) {
            "终端执行错误: ${e.message}"
        }
    }
}