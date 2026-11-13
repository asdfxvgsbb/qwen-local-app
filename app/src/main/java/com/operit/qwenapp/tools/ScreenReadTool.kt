package com.operit.qwenapp.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 屏幕理解工具（v9.22）：
 * 截图 → OCR 识别屏幕上的文字 + 位置（bounds）→ 返回结构化 JSON。
 * 让"手机操控"从「盲点固定坐标」升级为「先看屏幕再思考再点击」。
 *
 * 依赖 ML Kit 中文 OCR（bundled 模型，无 GMS 也能用，首次使用解压模型到本地）。
 * 使用方式：对模型说"看看屏幕/屏幕上有啥"，返回的文字带坐标，
 * 模型可据此决定点哪里（配合 control_device 的 tap）。
 */
class ScreenReadTool : Tool {
    override val name = "screen_read"
    override val description = "读取当前屏幕内容：截屏并识别屏幕上的文字及位置。" +
        "返回 JSON 数组，每项含 text（文字）和 bounds（左上角x,y/右下角x,y）。" +
        "用于先看屏幕再决定点击哪里（如找群、找按钮）。" +
        "如「看看屏幕」「屏幕上有什么」「读屏幕」。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "固定为 read"))
        )
        .put("required", JSONArray())

    override fun execute(args: JSONObject): String {
        // 1. 检查 Shizuku（需要 shell 权限截图）
        if (!ShizukuShell.isAvailable()) {
            return "无法截图：Shizuku 未授权。请先在 Shizuku 给本应用授权"
        }
        // 2. 截图前隐藏悬浮窗长条
        val ctx = ToolRegistry.appContext
        if (ctx != null) FloatWindowManager.hideForScreenshot(ctx)
        try { Thread.sleep(300) } catch (_: Exception) {}
        // 3. 截图
        val png = File("/sdcard/Download/screencap_${System.currentTimeMillis()}.png")
        val (c, o) = ShizukuShell.exec("screencap -p ${png.absolutePath}")
        if (ctx != null) FloatWindowManager.restoreAfterScreenshot(ctx)
        if (c != 0 || !png.exists() || png.length() < 1000L) {
            return "截图失败: $o"
        }
        // 4. OCR 识别
        return try {
            val bitmap: Bitmap = BitmapFactory.decodeFile(png.absolutePath)
                ?: return "截图文件无法解码"
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val image = InputImage.fromBitmap(bitmap, 0)
            // ML Kit Task 同步等待（60 秒超时）
            var result: com.google.mlkit.vision.text.Text? = null
            var error: Exception? = null
            val latch = CountDownLatch(1)
            recognizer.process(image)
                .addOnSuccessListener { r -> result = r; latch.countDown() }
                .addOnFailureListener { e -> error = e as? Exception ?: Exception(e.message); latch.countDown() }
            latch.await(60, TimeUnit.SECONDS)
            recognizer.close()
            val ocr = result ?: return "OCR 失败: ${error?.message ?: "超时"}"
            // 组装带坐标的文字列表
            val arr = JSONArray()
            for (block in ocr.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        val b = el.boundingBox ?: continue
                        arr.put(JSONObject()
                            .put("text", el.text)
                            .put("x", b.left)
                            .put("y", b.top)
                            .put("x2", b.right)
                            .put("y2", b.bottom))
                    }
                }
            }
            if (arr.length() == 0) "屏幕没有识别到文字（可能纯图标界面）"
            else "屏幕内容（文字+坐标，单位像素，点击用中心点≈(x+x2)/2,(y+y2)/2）：\n" + arr.toString(2)
        } catch (e: Exception) {
            "OCR 处理异常: ${e.message}"
        }
    }
}