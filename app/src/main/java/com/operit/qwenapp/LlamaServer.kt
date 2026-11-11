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
    private const val MODEL_DIR = "/sdcard/ai/qwen25-0.5b-tools"
    const val MODEL_FILE = "$MODEL_DIR/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
    const val BASE_URL = "http://127.0.0.1:$PORT/v1"

    private var process: Process? = null
    private val running = AtomicBoolean(false)

    // 最近 60 行 llama-server 输出日志缓冲（用于启动失败诊断）
    private val logBuffer = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** 取最近 n 行服务日志（启动失败时展示给用户） */
    fun lastLogLines(n: Int): List<String> {
        val all = logBuffer.toList()
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    /** 解压 assets 里的 llama-server 及依赖库到 filesDir（若已存在则跳过） */
    fun extractBinary(ctx: Context): File {
        val dir = File(ctx.filesDir, "llama")
        dir.mkdirs()
        val bin = File(dir, BIN_NAME)
        try {
            val names = ctx.assets.list("").orEmpty()
            // aapt 打包 assets 时会自动解压 .gz 文件，因此 APK 里可能是裸 ELF（llama-server）
            // 也可能是 gzip 流（llama-server.gz），两种都兼容
            val binSrc = when {
                names.contains("llama-server") -> "llama-server"
                names.contains("llama-server.gz") -> "llama-server.gz"
                else -> null
            }
            if (binSrc != null) {
                val target = File(dir, "llama-server")
                if (!target.exists() || target.length() < 100_000) {
                    copyAsset(ctx, binSrc, target)
                }
                target.setExecutable(true)
            }
            // 依赖库 .so.gz（若有）
            names.filter { it.startsWith("lib") && it.endsWith(".gz") }.forEach { name ->
                val target = File(dir, name.removeSuffix(".gz"))
                if (!target.exists() || target.length() < 100_000) {
                    copyAsset(ctx, name, target)
                }
                target.setExecutable(true)
            }
        } catch (e: Exception) {
            android.util.Log.e("LlamaServer", "extract failed", e)
        }
        bin.setExecutable(true)
        return bin
    }

    /** 从 assets 复制（.gz 走 gzip 解压，裸 ELF 直接 copy） */
    private fun copyAsset(ctx: Context, assetName: String, target: File) {
        ctx.assets.open(assetName).use { input ->
            FileOutputStream(target).use { out ->
                if (assetName.endsWith(".gz")) {
                    val gz = java.util.zip.GZIPInputStream(input)
                    gz.copyTo(out)
                    gz.close()
                } else {
                    input.copyTo(out)
                }
            }
        }
    }

    // ========== 模型文件选择/记忆 ==========
    private const val PREFS = "qwen_app"
    private const val KEY_MODEL_PATH = "model_path"

    /** 获取模型文件：优先用户手动选择的，否则默认固定路径 */
    fun getModelFile(ctx: Context): File {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_MODEL_PATH, null)
        if (saved != null) {
            val f = File(saved)
            if (f.exists() && f.length() > 100_000) return f
        }
        return File(MODEL_FILE)
    }

    /** 当前模型显示名（文件名，无路径），用于界面标题；任何 .gguf 都能显示 */
    fun getModelName(ctx: Context): String {
        return getModelFile(ctx).name
    }

    /** 从 SAF 选择器 URI 导入模型到私有目录并记住选择（不依赖存储权限） */
    fun importModelFromUri(ctx: Context, uri: android.net.Uri): Boolean {
        return try {
            val dir = File(ctx.filesDir, "models")
            dir.mkdirs()
            val target = File(dir, "model.gguf")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL_PATH, target.absolutePath).apply()
            true
        } catch (e: Exception) {
            android.util.Log.e("LlamaServer", "import model failed", e)
            false
        }
    }

    /** 启动服务（阻塞直到健康检查通过或超时） */
    fun start(ctx: Context): Boolean {
        if (running.get()) return true
        // 端口已有服务则直接复用（可能是外部/旧进程）
        if (isHealthy()) {
            running.set(true)
            return true
        }

        val bin = extractBinary(ctx)
        val model = getModelFile(ctx)
        if (!model.exists()) return false

        // 老的 kill 掉
        stop()

        val pb = ProcessBuilder(
            bin.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", PORT.toString(),
            "--n-gpu-layers", "0",
            "--threads", SettingsActivity.getThreads(ctx).toString(),
            "-c", SettingsActivity.getCtx(ctx).toString(),
            "-b", "512",
            "-ub", "512",
            // v0.9.0 已用 --load-mode 取代 --no-mmap（旧参数会直接报错退出）
            "--load-mode", "none",
            // v0.9.0 起 API 传 chat_template_kwargs.enable_thinking 已废弃被忽略，
            // 必须用启动参数 --reasoning off 才能真正关闭 Qwen3 思维链（快速回答开关）
            "--reasoning", if (SettingsActivity.getFastReply(ctx)) "off" else "auto"
        )
        // 依赖库所在目录设为 LD_LIBRARY_PATH（Android 版需要 dlopen libggml 等）
        val libDir = File(ctx.filesDir, "llama").absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        pb.redirectErrorStream(true)
        try {
            process = pb.start()
            // 读取日志线程（防止管道阻塞 + 收集到缓冲便于诊断）
            Thread {
                process?.inputStream?.bufferedReader()?.forEachLine { line ->
                    logBuffer.addLast(line)
                    while (logBuffer.size > 200) { logBuffer.pollFirst() }
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