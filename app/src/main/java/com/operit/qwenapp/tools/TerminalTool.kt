package com.operit.qwenapp.tools

import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 【会话式终端】在手机本地执行 shell 命令。
 * 升级自简易终端：改为 App 内维护一个常驻 sh 进程，
 * 通过 stdin 写命令 / stdout 读输出 —— cd、export、变量在会话内保留，
 * 体验接近完整终端。适合 ls/cat/echo/date/pwd/cd/export 等。
 */
class TerminalTool : Tool {
    override val name = "run_terminal"
    override val description = "内置终端（会话式）：执行 shell 命令，cd/export 等状态会保留。参数 cmd = 要执行的命令，" +
        "如 ls /sdcard、cat 文件路径、echo hello、date、pwd、cd /sdcard、export X=1。输入 reset 或 exit 可重置会话。"
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
        if (cmd.length > 800) return "错误：命令过长（最多800字符）"
        val timeoutSec = args.optInt("timeout", 10).coerceIn(1, 120)
        return runCmd(cmd, timeoutSec)
    }

    companion object {
        @Volatile private var proc: Process? = null
        @Volatile private var writer: OutputStream? = null

        /** 获取常驻 shell 会话（无则新建），进程存活时复用。
         *  优先用内置 Termux bash（真 Linux 环境）；未就绪退回系统 sh。 */
        @Synchronized
        private fun acquireSession(): Pair<Process, OutputStream> {
            val p = proc
            if (p != null && p.isAlive && writer != null) return p to writer!!
            val ctx = ToolRegistry.appContext
            val bash = ctx?.let { TermuxRuntime.bashPath(it) }
            val np: Process = if (bash != null) {
                val pb = ProcessBuilder(bash, "--noprofile", "--norc")
                pb.redirectErrorStream(true)
                // 注入 Termux 环境变量，让子进程能找到 lib/命令
                pb.environment().putAll(TermuxRuntime.env(ctx!!))
                pb.start()
            } else {
                ProcessBuilder("sh").redirectErrorStream(true).start()
            }
            proc = np
            writer = np.outputStream
            return np to writer!!
        }

        /** 销毁常驻会话（reset/exit） */
        @Synchronized
        fun destroySession() {
            try { proc?.destroy() } catch (_: Exception) {}
            proc = null
            writer = null
        }
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
        // 会话重置命令
        if (cmd == "reset" || cmd == "exit" || cmd == "clear") {
            destroySession()
            return "✅ 终端会话已重置（cd/export 环境已清空）"
        }
        return try {
            val (p, w) = acquireSession()
            // 结束标记：命令后追加 echo 标记，读到标记才算命令结束。
            // 避免 ping/logcat 等持续输出命令被"150ms 无新输出"误判提前结束。
            val marker = "__XDONE_${System.currentTimeMillis() % 1000000}__"
            w.write((cmd + "\n" + "echo $marker\n").toByteArray())
            w.flush()

            // 读取输出：循环读 stdout，直到出现结束标记；超时兜底
            val out = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            var forceStop = false
            var markerIdx = -1
            while (System.currentTimeMillis() < deadline && !forceStop) {
                while (reader.ready()) {
                    out.append(reader.read().toChar())
                }
                markerIdx = out.indexOf(marker)
                if (markerIdx >= 0) forceStop = true
                if (!forceStop) Thread.sleep(30)
            }
            if (System.currentTimeMillis() >= deadline) {
                destroySession()
                "命令超时（${timeoutSec}s），已终止并重置会话"
            } else {
                // 去掉结束标记及其所在行
                out.setLength(markerIdx)
                val text = out.toString().trim()
                when {
                    text.isBlank() -> "（无输出）"
                    text.length > 5000 -> text.takeLast(5000) + "\n…（输出已截断，只显示最后5000字符）"
                    else -> text
                }
            }
        } catch (e: Exception) {
            destroySession()
            "终端执行错误: ${e.message}"
        }
    }
}