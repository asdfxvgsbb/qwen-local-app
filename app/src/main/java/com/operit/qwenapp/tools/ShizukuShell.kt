package com.operit.qwenapp.tools

import android.content.Context
import java.io.BufferedReader

/**
 * Shizuku 提权封装（v9.21）：
 * 通过 Shizuku 以系统权限（shell 用户）执行命令，实现"操控手机"。
 * - 未安装 Shizuku / 未授权 / 失败：isAvailable=false，上层自动降级内置终端。
 * - 授权：首次使用调用 requestPermission() 引导用户在 Shizuku 里授权。
 */
object ShizukuShell {

    /** Shizuku 是否可用（已授权） */
    fun isAvailable(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** 是否已安装 Shizuku（服务存在） */
    fun isInstalled(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Throwable) { false }

    /** 引导用户去授权（返回 true 表示已发引导） */
    fun requestPermission(activity: android.app.Activity?): Boolean {
        return try {
            if (!isInstalled()) return false
            // 老版本 Shizuku（系统授权弹窗）无法直接用 API 引导，交由用户去 Shizuku App 授权
            if (rikka.shizuku.Shizuku.isPreV11() || rikka.shizuku.Shizuku.getVersion() < 11) {
                return false
            }
            rikka.shizuku.Shizuku.requestPermission(0)
            true
        } catch (e: Throwable) { false }
    }

    /** 以系统权限执行命令（sh -c），返回 (exitCode, 输出) */
    fun exec(cmd: String, timeoutMs: Long = 15000): Pair<Int, String> {
        return try {
            if (!isAvailable()) return -1 to "Shizuku 不可用"
            val process = reflectNewProcess(cmd)
            val out = StringBuilder()
            val err = StringBuilder()
            // 读取线程
            val reader = Thread {
                try {
                    BufferedReader(process.inputStream.reader()).forEachLine { out.append(it).append("\n") }
                } catch (_: Exception) {}
            }.apply { start() }
            val errReader = Thread {
                try {
                    BufferedReader(process.errorStream.reader()).forEachLine { err.append(it).append("\n") }
                } catch (_: Exception) {}
            }.apply { start() }

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            reader.join(2000)
            errReader.join(2000)
            val combined = (out.toString() + err.toString()).trim()
            if (!finished) {
                // 超时：先温和 destroy，再强制，然后短暂等退出；无论如何不再调 exitValue()（会抛异常）
                try { process.destroy() } catch (_: Exception) {}
                try { process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                return 124 to (combined.ifEmpty { "[超时] 命令执行超过 ${timeoutMs}ms" })
            }
            // 只有已正常退出才取 exitValue
            val code = try { process.exitValue() } catch (_: Exception) { 0 }
            code to combined
        } catch (e: Exception) {
            -1 to "Shizuku 执行异常: ${e.message}"
        }
    }

    /** 输入文字（KEYCODE 方式，仅 ASCII 可靠）；中文需 adb IME，这里先返回提示 */
    fun inputText(text: String): Pair<Int, String> {
        // 尝试用 input text（空格转 %s）
        val safe = text.replace(" ", "%s").replace("\\", "\\\\").replace("\"", "\\\"")
        return if (safe.all { it.code < 128 }) {
            exec("input text '$safe'")
        } else {
            -1 to "输入法暂不支持中文直接输入（可尝试先用备忘录粘贴）"
        }
    }

    /**
     * 反射调用 Shizuku.newProcess（13.1.5 中为 private static，无公开替代 API）。
     * Shizuku 是普通库类而非系统 hidden API，反射 setAccessible(true) 无兼容性风险。
     * 签名：private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir)
     */
    private fun reflectNewProcess(cmd: String): Process {
        val method = rikka.shizuku.Shizuku::class.java
            .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }
}