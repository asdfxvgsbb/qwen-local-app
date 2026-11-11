package com.operit.qwenapp.tools

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 工具注册表：集中管理所有工具，生成 OpenAI tools 数组，按名执行
 */
object ToolRegistry {

    /** 由 MainActivity 注入的 Application Context（工具内需要环境时使用） */
    @Volatile
    var appContext: Context? = null

    /** 由 MainActivity 注入的 Activity 引用（危险工具弹确认框用） */
    @Volatile
    var currentActivity: Activity? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /**
     * 危险工具确认弹窗：主线程弹 AlertDialog，等待用户点「执行/取消」。
     * 超时（默认 30 秒）视为取消，返回 false。
     */
    fun confirm(title: String, msg: String): Boolean {
        val act = currentActivity ?: return false
        val latch = CountDownLatch(1)
        var result = false
        act.runOnUiThread {
            AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("执行") { _, _ -> result = true; latch.countDown() }
                .setNegativeButton("取消") { _, _ -> result = false; latch.countDown() }
                .show()
        }
        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            return false
        }
        return result
    }

    private val tools: List<Tool> = listOf(
        CalculatorTool(),
        WeatherTool(),
        GetTimeTool(),
        CodeRunnerTool(),   // 【编程类工具】运行 Python / JS / Shell 代码
        FileTool(),
        TerminalTool(),     // 【内置终端】直接执行 shell 命令
        DeviceInfoTool(),   // 【设备信息】电量/存储/内存
        UnitConvertTool(),  // 【单位换算】长度/重量/温度/数据
        MemorizeTool(),     // 【记忆】记住用户偏好/备忘
        LaunchAppTool(),    // 【打开应用】根据名称匹配并启动已安装应用
        SetReminderTool(),  // 【提醒】倒计时/闹钟提醒
        ClipboardTool(),    // 【剪贴板】读写系统剪贴板
        OpenWebsiteTool(),  // 【打开网页】浏览器打开网址
        RandomTool(),       // 【随机】随机数/随机选项
        VibrateTool(),      // 【震动】手机震动
        VolumeTool(),       // 【音量】媒体音量调节
        FlashlightTool(),   // 【手电筒】闪光灯开关
        BrightnessTool(),   // 【亮度】屏幕亮度调节
        WebSearchTool(),    // 【网络搜索】联网搜索（需设置开关）
        WebSummaryTool(),   // 【网页摘要】抓取网页正文
        ExchangeRateTool(), // 【汇率】实时汇率换算
        SmsTool(),          // 【发短信】需权限+确认
        CallTool()          // 【打电话】需权限+确认
    )

    /** 生成传给 /v1/chat/completions 的 tools 参数（names 为 null 时传全部） */
    fun toToolsJson(names: List<String>? = null): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            if (names != null && t.name !in names) continue
            val fn = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", t.parametersSchema)
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    /** 按名执行工具，返回结果字符串；未找到返回错误 */
    fun execute(name: String, arguments: String): String {
        val tool = tools.find { it.name == name }
            ?: return "错误：未知工具 $name"
        return try {
            val args = if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
            tool.execute(args)
        } catch (e: Exception) {
            "工具执行出错: ${e.message}"
        }
    }
}