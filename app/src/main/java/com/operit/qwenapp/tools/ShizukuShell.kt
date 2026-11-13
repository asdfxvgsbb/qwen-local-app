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
        dev.rikka.shizuku.Shizuku.pingBinder() &&
            dev.rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** 是否已安装 Shizuku（服务存在） */
    fun isInstalled(): Boolean = try {
        dev.rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Throwable) { false }

    /** 引导用户去授权（返回 true 表示已发引导） */
    fun requestPermission(activity: android.app.Activity?): Boolean {
        return try {
            if (!isInstalled()) return false
            if (dev.rikka.shizuku.Shizuku.isPreV11() || dev.rikka.shizuku.Shizuku.getVersion() < 11) {
                dev.rikka.shizuku.Shizuku.shouldShowSystemPermissionDialog(activity?.packageName ?: "")
                return true
            }
            dev.rikka.shizuku.Shizuku.requestPermission(0)
            true
        } catch (e: Throwable) { false }
    }

    /** 以系统权限执行命令（sh -c），返回 (exitCode, 输出) */
    fun exec(cmd: String, timeoutMs: Long = 15000): Pair<Int, String> {
        return try {
            if (!isAvailable()) return -1 to "Shizuku 不可用"
            val process = dev.rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
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
            if (!finished) {
                process.destroy()
                return 124 to (out.toString().trim() + "\n[超时]".trim())
            }
            val code = process.exitValue()
            val combined = (out.toString() + err.toString()).trim()
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
}