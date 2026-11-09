package com.operit.qwenapp

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama-server 进程管理：
 * 1. 首次启动时把 APK assets 里的 llama-server 二进制解压到私有目录
 * 2. 用 ProcessBuilder 启动（监听 127.0.0.1:8080）
 * 3. 轮询 /health 确认就绪
 */
object LlamaServer {

    private const val PORT = 8080
    private const val BIN_NAME = "llama-server"
    private const val MODEL_DIR = "/sdcard/ai/qwen25-1.5b-tools"
    const val MODEL_FILE = "$MODEL_DIR/Qwen2.5-1.5B-tools-Q5_K_M.gguf"
    const val BASE_URL = "http://127.0.0.1:$PORT/v1"

    private var process: Process? = null
    private val running = AtomicBoolean(false)

    /** 解压 assets 里的 llama-server 及依赖库到 filesDir（若已存在则跳过） */
    fun extractBinary(ctx: Context): File {
        val dir = File(ctx.filesDir, "llama")
        dir.mkdirs()
        val bin = File(dir, BIN_NAME)
        // 解压所有 assets 下以 .gz 结尾的文件（llama-server.gz + lib*.so.gz）
        try {
            ctx.assets.list("").orEmpty()
                .filter { it.endsWith(".gz") }
                .forEach { name ->
                    val target = File(dir, name.removeSuffix(".gz"))
                    if (!target.exists() || target.length() < 100_000) {
                        ctx.assets.open(name).use { input ->
                            FileOutputStream(target).use { out ->
                                val gz = java.util.zip.GZIPInputStream(input)
                                gz.copyTo(out)
                                gz.close()
                            }
                        }
                        if (name.startsWith("lib")) target.setExecutable(true)
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("LlamaServer", "extract failed", e)
        }
        bin.setExecutable(true)
        return bin
    }

    /** 启动服务（阻塞直到健康检查通过或超时） */
    fun start(ctx: Context): Boolean {
        if (running.get()) return true

        val bin = extractBinary(ctx)
        val model = File(MODEL_FILE)
        if (!model.exists()) return false

        // 老的 kill 掉
        stop()

        val pb = ProcessBuilder(
            bin.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", PORT.toString(),
            "--n-gpu-layers", "0",
            "--threads", "6",
            "-c", "1024",
            "-b", "1024",
            "-ub", "1024",
            "--no-mmap"
        )
        // 依赖库所在目录设为 LD_LIBRARY_PATH（Android 版需要 dlopen libggml 等）
        val libDir = File(ctx.filesDir, "llama").absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        pb.redirectErrorStream(true)
        try {
            process = pb.start()
            // 读取日志线程（防止管道阻塞）
            Thread {
                process?.inputStream?.bufferedReader()?.forEachLine { line ->
                    android.util.Log.d("LlamaServer", line)
                }
            }.start()
            // 轮询健康检查，最多 120 秒
            for (i in 0 until 240) {
                if (isHealthy()) {
                    running.set(true)
                    return true
                }
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            android.util.Log.e("LlamaServer", "start failed", e)
        }
        return false
    }

    fun stop() {
        running.set(false)
        process?.let {
            try { it.destroy() } catch (_: Exception) {}
            try { it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        process = null
    }

    fun isRunning(): Boolean = running.get()

    /** HTTP 健康检查 */
    fun isHealthy(): Boolean {
        return try {
            val url = java.net.URI("http://127.0.0.1:$PORT/health").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }
}