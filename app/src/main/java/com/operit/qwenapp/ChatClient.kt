package com.operit.qwenapp

import com.operit.qwenapp.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 本地模型 API 客户端：
 * 1. 发送消息 → 模型可能返回文本 或 工具调用
 * 2. 若是工具调用，本地执行 → 结果喂回 → 模型总结
 * 3. 循环最多 5 轮，避免死循环
 */
class ChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)  // 本地模型慢，给足时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val SYSTEM_PROMPT: String
        get() {
            // 从设置读取自定义提示词，未设置用默认
            val ctx = ToolRegistry.appContext
            val custom = ctx?.let { SettingsActivity.getPrompt(it) }.orEmpty()
            return if (custom.isNotBlank()) custom else
                "你是本地Agent，运行在用户手机上。\n" +
                "你拥有以下工具，当用户说的话出现对应关键词时，工具会自动执行，结果会以「工具结果」形式发给你，你必须基于结果回答：\n" +
                buildToolsHelp() +
                "\n规则：\n" +
                "1. 用户日常闲聊（不含任何关键词）时直接正常回答。\n" +
                "2. 用户提到关键词时，不要自己编造数据，等工具结果回来后再回答。\n" +
                "3. 工具结果已给出时，直接根据结果告诉用户，不要重复生成JSON。\n"
        }

    /** 从 TOOL_KEYWORDS 动态生成「工具名：关键词」说明，保证和实际触发逻辑一致 */
    private fun buildToolsHelp(): String {
        val names = mapOf(
            "calculator" to "计算器",
            "get_weather" to "天气查询",
            "get_current_time" to "日期时间",
            "run_code" to "代码运行",
            "file_ops" to "文件读写",
            "run_terminal" to "终端命令",
            "device_info" to "设备信息",
            "unit_convert" to "单位换算",
            "memorize" to "记忆",
            "launch_app" to "打开应用",
            "set_reminder" to "提醒闹钟",
            "clipboard" to "剪贴板",
            "open_website" to "打开网页",
            "random" to "随机",
            "web_search" to "网络搜索",
            "web_summary" to "网页摘要",
            "exchange_rate" to "汇率换算",
            "volume" to "音量调节",
            "brightness" to "屏幕亮度",
            "flashlight" to "手电筒",
            "vibrate" to "震动",
            "send_sms" to "发短信",
            "make_call" to "打电话"
        )
        val sb = StringBuilder()
        var first = true
        for ((tool, kws) in TOOL_KEYWORDS) {
            val label = names[tool] ?: tool
            if (!first) sb.append("\n")
            first = false
            sb.append("• ").append(label).append("：出现「").append(kws.joinToString("、")).append("」时触发")
        }
        return sb.toString()
    }

    /** 关键词 → 工具名映射：按用户输入命中关键词，只带相关工具（避免9个工具全塞进prompt） */
    /** 关键词 → 工具名映射：按用户输入命中关键词，只带相关工具（避免9个工具全塞进prompt）
 * 注意：尽量用“长短语/精准词”，避免“现在/多大/打开/等于/我叫”这类高频口语词，防止普通聊天误触发工具 */
    private val TOOL_KEYWORDS = mapOf(
        "calculator" to listOf("计算", "算一下", "数学", "加减乘除", "乘以", "除以", "等于多少", "等于几", "算一算", "帮我算"),
        "get_weather" to listOf("天气", "下雨", "台风", "气温", "几度", "温度", "天气怎么样"),
        "get_current_time" to listOf("时间", "几点", "日期", "星期", "几点了", "几点钟", "现在几点", "现在时间", "现在日期", "今天几号", "今天星期"),
        "run_code" to listOf("代码", "脚本", "编程", "javascript", "python", "执行代码", "写个程序", "跑一下"),
        "file_ops" to listOf("文件", "读写", "保存", "读取", "写入", "读文件", "写文件", "创建文件"),
        "run_terminal" to listOf("终端", "命令", "ls ", "cat ", "pwd", "echo ", "shell", "执行命令"),
        "device_info" to listOf("设备信息", "电池", "电量", "内存多大", "存储多大", "手机配置", "还剩多少空间", "运行内存"),
        "unit_convert" to listOf("换算", "单位换算", "多少米", "多少公里", "多少斤", "多少克", "摄氏度", "华氏", "kb", "mb", "gb"),
        "memorize" to listOf("记住", "记一下", "记忆", "备忘录", "别忘", "记住我叫", "记住我的", "我的生日", "帮我记"),
        "launch_app" to listOf("打开", "启动", "开启", "帮我打开", "打开一下", "安装的"),
        "set_reminder" to listOf("提醒我", "提醒一下", "闹钟", "倒计时", "分钟后提醒", "秒后提醒", "小时后提醒", "待办提醒"),
        "clipboard" to listOf("剪贴板", "复制到", "粘贴出来", "复制这个", "帮我复制", "复制网址"),
        "open_website" to listOf("打开网页", "打开网站", "用浏览器", "上网搜", "浏览网页", "打开百度", "打开淘宝", "打开微博", "打开b站", "打开bilibili", "打开知乎"),
        "random" to listOf("随机数", "随机选", "帮我选一个", "随机数字", "摇一摇", "抽一个", "抽签", "掷骰子"),
        "web_search" to listOf("搜索", "查一下", "搜一下", "搜搜", "百度一下", "网上查", "查询一下", "看看网上怎么说", "搜资料"),
        "web_summary" to listOf("网页摘要", "看看这个网页", "网页内容", "网址摘要", "抓取网页"),
        "exchange_rate" to listOf("汇率", "换算成人民币", "美元等于", "欧元等于", "日元等于", "港币等于", "英镑等于", "换成人民币"),
        "volume" to listOf("音量调大", "音量调小", "声音大点", "声音小点", "大声点", "小声点", "静音", "取消静音", "把声音"),
        "brightness" to listOf("屏幕亮度", "调亮", "调暗", "亮度调到", "屏幕亮", "屏幕暗", "亮一点", "暗一点"),
        "flashlight" to listOf("手电筒", "闪光灯", "开灯", "关灯"),
        "vibrate" to listOf("震动一下", "振动一下", "手机震动"),
        "send_sms" to listOf("发短信", "发信息给", "短信给", "发条短信", "短信发"),
        "make_call" to listOf("打电话给", "打电话", "拨号", "呼叫", "打给")
    )

    /** 根据用户消息返回需要带的工具名列表（未命中则空 = 不带工具直接聊天） */
    private fun selectedTools(userText: String): List<String> {
        val t = userText.lowercase()
        var list = TOOL_KEYWORDS.filter { (_, kws) -> kws.any { t.contains(it) } }.keys.toList()
        // 优先级：命中 open_website（打开百度/淘宝/b站等）时，去掉 launch_app 的宽泛"打开"命中，避免同时开App又开网页
        if ("open_website" in list && "launch_app" in list) {
            list = list.filter { it != "launch_app" }
        }
        // 优先级：命中 flashlight（打开手电筒/开灯）时，去掉 launch_app 的"打开"命中
        if ("flashlight" in list && "launch_app" in list) {
            list = list.filter { it != "launch_app" }
        }
        // 优先级：命中 exchange_rate（换算成人民币）时，去掉 unit_convert 的"换算"命中
        if ("exchange_rate" in list && "unit_convert" in list) {
            list = list.filter { it != "unit_convert" }
        }
        return list
    }

    /**
     * 从用户文本自动构造工具参数（供"强行调用工具"用）。
     * 无法可靠提取参数的工具返回 null（跳过强行调用，避免参数错误）。
     */
    private fun autoArgs(toolName: String, userText: String): JSONObject? {
        return when (toolName) {
            "get_current_time" -> JSONObject()
            "device_info" -> JSONObject().put("info", "all")
            "calculator" -> {
                // 提取纯算术表达式：如 "15*3+2" / "2的平方+5"（数字+运算符）
                val m = Regex("[0-9+\\-*/().%\\s]+").find(userText)?.value?.trim()
                if (m.isNullOrEmpty() || !m.any { it.isDigit() }) null
                else JSONObject().put("expr", m)
            }
            "get_weather" -> {
                // 提取"XXX天气"，如"北京天气"、"广州的天气"
                val m = Regex("([\\u4e00-\\u9fa5A-Za-z]{1,6})[的]?天气").find(userText)
                val city = m?.groupValues?.get(1)
                if (city.isNullOrEmpty()) null else JSONObject().put("city", city)
            }
            "launch_app" -> {
                // 提取应用名：去掉"帮我打开/打开/启动"等前缀，取剩余部分
                var t = userText
                val pre = listOf("帮我打开一下", "帮我打开", "打开一下", "开启一下", "帮我启动", "请打开", "打开", "启动", "开启", "帮我开")
                    .firstOrNull { t.contains(it) }
                if (pre != null) t = t.substringAfter(pre).trim()
                t = t.trim('。', '！', '？', '，', '？', ' ', '.', '!', '?')
                // 去掉常见的客套词尾巴
                t = t.removeSuffix("一下").removeSuffix("吧").removeSuffix("啊").trim()
                if (t.isEmpty() || t.length > 12) null else JSONObject().put("name", t)
            }
            "set_reminder" -> {
                // 提取延迟秒数："X分钟/秒/小时后"
                val m = Regex("(\\d+)\\s*(秒|分钟|小时)后").find(userText)
                if (m == null) {
                    // 没有明确时间，默认 10 分钟
                    val msg = userText.replace(Regex("提醒我|提醒一下|帮我|设置|闹钟|倒计时"), "").trim('。', '！', '？', ' ')
                    if (msg.isEmpty()) null else JSONObject().put("delay_seconds", 600).put("message", msg)
                } else {
                    val num = m.groupValues[1].toLong()
                    val unit = m.groupValues[2]
                    val sec = when (unit) {
                        "秒" -> num
                        "分钟" -> num * 60
                        "小时" -> num * 3600
                        else -> num
                    }
                    val msg = userText.replace(m.value, "").replace(Regex("提醒我|提醒一下|帮我|设置|闹钟|倒计时"), "").trim('。', '！', '？', ' ')
                    JSONObject().put("delay_seconds", sec).put("message", msg.ifEmpty { "提醒" })
                }
            }
            "open_website" -> {
                // "打开百度" → https://www.baidu.com；"打开b站" → bilibili
                val known = mapOf(
                    "百度" to "www.baidu.com", "淘宝" to "www.taobao.com", "微博" to "weibo.com",
                    "b站" to "www.bilibili.com", "bilibili" to "www.bilibili.com", "知乎" to "www.zhihu.com",
                    "京东" to "www.jd.com", "抖音" to "www.douyin.com", "微信" to "weixin.qq.com",
                    "腾讯" to "www.tencent.com", "阿里巴巴" to "www.alibaba.com", "网易" to "www.163.com"
                )
                val hit = known.entries.firstOrNull { (k, _) -> userText.contains(k) }
                if (hit != null) JSONObject().put("url", hit.value)
                else null // 其余情况交给模型带 url
            }
            "random" -> {
                // "1到100随机数" 或 "帮我选一个：A/B/C"
                val m = Regex("(\\d+)\\s*到\\s*(\\d+)").find(userText)
                if (m != null) {
                    JSONObject().put("type", "number").put("min", m.groupValues[1].toInt()).put("max", m.groupValues[2].toInt())
                } else {
                    // 检查选项：用 / 或 、 或 ， 分隔
                    val opts = userText.split(Regex("[/、，,]")).map { it.trim().removePrefix("帮我选一个").removePrefix("帮我选").removeSuffix("吧") }.filter { it.isNotBlank() && it.length <= 6 }
                    if (opts.size >= 2) {
                        val arr = org.json.JSONArray()
                        opts.forEach { arr.put(it) }
                        JSONObject().put("type", "choice").put("options", arr)
                    } else null
                }
            }
            "web_search" -> {
                // "搜索 XX" / "查一下 XX" → query
                var t = userText
                val pre = listOf("搜索一下", "搜索", "搜一下", "搜搜", "查一下", "百度一下", "网上查", "查询一下", "搜资料", "搜")
                    .firstOrNull { t.contains(it) }
                if (pre != null) t = t.substringAfter(pre).trim()
                t = t.trim('。', '！', '？', '，', ' ', '.', '!', '?', '：', ':')
                if (t.isEmpty() || t.length > 60) null else JSONObject().put("query", t)
            }
            "web_summary" -> {
                val m = Regex("(https?://[\\w\\-./?%&=#]+)").find(userText)
                val url = m?.groupValues?.get(1)
                if (url.isNullOrEmpty()) null else JSONObject().put("url", url)
            }
            "exchange_rate" -> {
                // "1美元等于多少人民币" / "100美金换日元" / "美元兑人民币"
                val names = mapOf(
                    "美元" to "USD", "美金" to "USD", "人民币" to "CNY", "欧元" to "EUR",
                    "日元" to "JPY", "港币" to "HKD", "英镑" to "GBP", "韩元" to "KRW",
                    "澳元" to "AUD", "加元" to "CAD", "卢布" to "RUB", "泰铢" to "THB"
                )
                // 金额
                val amt = Regex("(\\d+(?:\\.\\d+)?)").find(userText)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
                // 找源货币和目标货币
                val hits = names.entries.filter { (k, _) -> userText.contains(k) }.map { it.value }
                val from = hits.getOrNull(0)
                val to = hits.getOrNull(1)
                if (from == null || to == null) null
                else JSONObject().put("amount", amt).put("from", from).put("to", to)
            }
            "volume" -> {
                val action = when {
                    userText.contains("静音") || userText.contains("无声") -> "mute"
                    userText.contains("取消静音") || userText.contains("恢复声音") -> "unmute"
                    userText.contains("调大") || userText.contains("大点") || userText.contains("大声") -> "up"
                    userText.contains("调小") || userText.contains("小点") || userText.contains("小声") -> "down"
                    else -> null
                }
                if (action == null) null else JSONObject().put("action", action)
            }
            "brightness" -> {
                val m = Regex("(\\d+)\\s*%?").find(userText)
                if (m != null && userText.contains("亮度")) {
                    JSONObject().put("level", m.groupValues[1].toInt().coerceIn(0, 100))
                } else {
                    val action = when {
                        userText.contains("调暗") || userText.contains("暗一点") || userText.contains("屏幕暗") || userText.contains("暗点") -> "down"
                        userText.contains("调亮") || userText.contains("亮一点") || userText.contains("屏幕亮") || userText.contains("亮点") -> "up"
                        else -> null
                    }
                    if (action == null) null else JSONObject().put("action", action)
                }
            }
            "flashlight" -> {
                val on = userText.contains("开") && !userText.contains("关")
                JSONObject().put("on", on)
            }
            "vibrate" -> JSONObject()
            "send_sms" -> {
                // "发短信给10086：查话费" / "发短信给13800138000 内容"
                val phone = Regex("(\\d{5,13})").find(userText)?.groupValues?.get(1)
                if (phone == null) null
                else {
                    var msg = userText.replace(Regex("发短信|发信息给|短信给|发条短信|短信发|给|发送|短信"), "").trim()
                    msg = msg.replace(phone, "").trim('。', '！', '？', '，', ' ', '：', ':', '.', '!', '?')
                    if (msg.isEmpty()) msg = "（无内容）"
                    JSONObject().put("phone", phone).put("message", msg)
                }
            }
            "make_call" -> {
                // "打电话给10086" / "打给13800138000"
                val phone = Regex("(\\d{5,13})").find(userText)?.groupValues?.get(1)
                if (phone == null) null else JSONObject().put("phone", phone)
            }
            // run_code / file_ops / run_terminal / unit_convert / memorize / clipboard 参数复杂，不强行调用
            else -> null
        }
    }

    /**
     * 强行执行命中的工具（适配没有 tool calling 能力的模型）：
     * 模型首轮没输出 tool_calls 时，由 App 直接执行工具并把结果按 OpenAPI 格式喂回，
     * 再让模型基于真实工具结果总结回答。
     */
    private fun forceRunTools(
        toolNames: List<String>,
        userText: String,
        onToolCall: (name: String, args: String, result: String, running: Boolean) -> Unit
    ): Boolean {
        var executed = false
        var idx = 0
        for (tn in toolNames) {
            val args = autoArgs(tn, userText) ?: continue
            val callId = "auto_${idx++}"
            // 合法顺序：assistant(带 tool_calls) + tool(结果)
            history.add(JSONObject()
                .put("role", "assistant")
                .put("content", "")
                .put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", callId)
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", tn)
                        .put("arguments", args.toString())))))
            onToolCall(tn, args.toString(), "", true)
            val result = try {
                ToolRegistry.execute(tn, args.toString())
            } catch (e: Exception) {
                "工具执行错误: ${e.message}"
            }
            onToolCall(tn, args.toString(), result, false)
            history.add(JSONObject()
                .put("role", "tool")
                .put("tool_call_id", callId)
                .put("content", result))
            executed = true
        }
        return executed
    }

    /** 消息历史（OpenAI 格式） */
    private val history = mutableListOf<JSONObject>()

    fun resetHistory() {
        history.clear()
    }

    /**
     * 发送用户消息，返回模型最终回复文本
     * onToolCall: 回调工具调用状态（name=工具名, args=参数, result=结果, running=是否进行中）
     */
    suspend fun chat(
        userText: String,
        onToolCall: (name: String, args: String, result: String, running: Boolean) -> Unit
    ): String = withContext(Dispatchers.IO) {
        history.add(JSONObject().put("role", "user").put("content", userText))

        // 按关键词只带相关工具（普通闲聊不带，避免小模型乱调用 + 省上下文）
        var toolNames = selectedTools(userText)

        // 工具调用循环，最多 5 轮
        for (round in 0 until 5) {
            val body = buildBody(stream = false, toolNames = toolNames)

            val resp = post(body)
            val choice = resp.getJSONArray("choices").getJSONObject(0)
            val msg = choice.getJSONObject("message")
            val finish = choice.optString("finish_reason")

            // 模型要调用工具？
            val toolCalls = msg.optJSONArray("tool_calls")
            if (finish == "tool_calls" && toolCalls != null && toolCalls.length() > 0) {
                // 记录 assistant 消息（含 tool_calls）
                history.add(msg)

                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    val name = fn.optString("name")
                    val args = fn.optString("arguments", "{}")
                    val callId = tc.optString("id", "call_$i")

                    onToolCall(name, args, "", true)
                    // 本地执行工具
                    val result = ToolRegistry.execute(name, args)
                    onToolCall(name, args, result, false)

                    // 把工具结果作为 tool 消息加入历史
                    history.add(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result))
                }
                // 继续下一轮，让模型根据结果总结
            } else {
                // 正常文本回答（模型没调工具）
                val content = msg.optString("content", "")
                // 兜底：带 tools 时模型可能把 JSON 当普通文本输出（小模型常见）
                // 检测到这种"伪工具调用"就去掉 tools 重新请求，强制模型说人话
                val looksLikeToolJson = toolNames.isNotEmpty() && (
                    content.contains("{\"name\"") ||
                    content.contains("tool_calls") ||
                    (content.trimStart().startsWith("{") && content.contains("\"arguments\""))
                    )
                // 强制工具适配：先真执行命中的工具（模型没调工具能力时也执行）
                // forceRunTools 内部已加入 assistant(带tool_calls)+tool 消息，这里不要再 add
                // 顺序在前：模型把工具调用当文本输出时，先真正执行工具
                if (toolNames.isNotEmpty() && forceRunTools(toolNames, userText, onToolCall)) {
                    toolNames = emptyList()
                    continue
                }
                if (looksLikeToolJson) {
                    toolNames = emptyList()
                    continue
                }
                history.add(msg)
                return@withContext content
            }
        }
        "（工具调用超过上限，请重试）"
    }

    private fun buildBody(stream: Boolean, toolNames: List<String> = emptyList()): JSONObject {
        val body = JSONObject()
            .put("model", "local-qwen")   // 模型名，服务器不校验
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                history.forEach { put(it) }
            })
            .put("stream", stream)
            .put("max_tokens", 512)
        // 快速回答：关闭 Qwen3 思维链（设置页开关，默认开）
        // llama.cpp 通过 chat_template_kwargs.enable_thinking=false 让 Qwen3 跳过思考直接回答
        val ctx = ToolRegistry.appContext
        if (ctx != null && SettingsActivity.getFastReply(ctx)) {
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        // 只带命中的工具（普通闲聊/未命中 = 不带，省上下文 + 避免小模型乱调用）
        if (toolNames.isNotEmpty()) {
            body.put("tools", ToolRegistry.toToolsJson(toolNames))
                .put("tool_choice", "auto")
        }
        return body
    }

    /**
     * 流式版本：逐字回调 onDelta，直到完整回复。
     * 兼容流式分片中的工具调用（llama-server / OpenAI 格式），
     * 工具调用完成后自动把结果喂回并继续流式输出最终回答。
     */
    suspend fun chatStream(
        userText: String,
        onToolCall: (name: String, args: String, result: String, running: Boolean) -> Unit,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        history.add(JSONObject().put("role", "user").put("content", userText))

        // 按关键词只带相关工具（普通闲聊不带，避免小模型乱调用 + 省上下文）
        var toolNames = selectedTools(userText)

        for (round in 0 until 5) {
            val body = buildBody(stream = true, toolNames = toolNames)
            val sb = StringBuilder()
            var suspicious = false
            // 流式过程中收集的 tool_calls 分片（index -> 累加 JSON 字符串）
            val toolCalls = HashMap<Int, JSONObject>()
            val toolOrder = ArrayList<Int>()

            val request = Request.Builder()
                .url(LlamaServer.BASE_URL + "/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
                // SSE 逐行读取
                resp.body?.byteStream()?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val json = try { JSONObject(data) } catch (e: Exception) { continue }
                            val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                ?: continue
                            val finish = json.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason").orEmpty()
                            // 注意：org.json 的 optString 对 JSON null 会返回字符串 "null"，必须显式判断
                            val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                if (!suspicious && (content.contains("<tool_call") || content.contains("{\"name\"")
                                        || content.contains("\"arguments\"") || content.contains("tool_calls"))) {
                                    suspicious = true
                                }
                                if (!suspicious) {
                                    sb.append(content)
                                    onDelta(content)
                                }
                            }
                            val tc = delta.optJSONArray("tool_calls")
                            if (tc != null) {
                                for (i in 0 until tc.length()) {
                                    val tcObj = tc.getJSONObject(i)
                                    val idx = tcObj.optInt("index", 0)
                                    val fn = tcObj.optJSONObject("function") ?: continue
                                    val acc = toolCalls.getOrPut(idx) {
                                        toolOrder.add(idx)
                                        JSONObject().put("id", tcObj.optString("id", "call_$idx"))
                                            .put("type", "function")
                                            .put("function", JSONObject().put("name", "").put("arguments", ""))
                                    }
                                    if (fn.has("name") && !fn.isNull("name") && fn.optString("name").isNotEmpty()) {
                                        acc.getJSONObject("function").put("name",
                                            acc.getJSONObject("function").optString("name") + fn.optString("name"))
                                    }
                                    if (fn.has("arguments") && !fn.isNull("arguments")) {
                                        acc.getJSONObject("function").put("arguments",
                                            acc.getJSONObject("function").optString("arguments") + fn.optString("arguments"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 若有工具调用，则执行并把结果喂回；否则返回文本
            if (toolCalls.isNotEmpty()) {
                // 组装 assistant 消息（带 tool_calls）加入历史
                val asstMsg = JSONObject()
                    .put("role", "assistant")
                    .put("content", sb.toString())
                    .put("tool_calls", JSONArray().apply {
                        for (idx in toolOrder) put(toolCalls[idx])
                    })
                history.add(asstMsg)

                for (idx in toolOrder) {
                    val tc = toolCalls[idx] ?: continue
                    val name = tc.getJSONObject("function").optString("name")
                    val args = tc.getJSONObject("function").optString("arguments", "{}")
                    val callId = tc.optString("id", "call_$idx")
                    onToolCall(name, args, "", true)
                    val result = try {
                        ToolRegistry.execute(name, args)
                    } catch (e: Exception) {
                        "工具执行错误: ${e.message}"
                    }
                    onToolCall(name, args, result, false)
                    history.add(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result))
                }
                // 继续下一轮流式输出最终回答
            } else {
                // 纯文本回答结束
                val content = sb.toString()
                // 兜底：带 tools 时模型可能把 JSON 当普通文本输出（小模型常见）
                val looksLikeToolJson = toolNames.isNotEmpty() && (
                    content.contains("{\"name\"") ||
                    content.contains("tool_calls") ||
                    (content.trimStart().startsWith("{") && content.contains("\"arguments\""))
                    )
                // 强制工具适配：先真执行命中的工具（模型没调工具能力时也执行）
                // forceRunTools 内部已加入 assistant(带tool_calls)+tool 消息，这里不要再 add
                // 顺序在前：模型把工具调用当文本输出时，先真正执行工具
                if (toolNames.isNotEmpty() && forceRunTools(toolNames, userText, onToolCall)) {
                    toolNames = emptyList()
                    continue
                }
                if (looksLikeToolJson) {
                    // 流式已把内容显示到界面上，这里只是决定返回什么
                    // 去掉 tools 重新请求，强制模型说人话（不带工具的轮次不再显示流式）
                    toolNames = emptyList()
                    val retry = buildBody(stream = false, toolNames = emptyList())
                    val resp = post(retry)
                    val c = resp.getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("message")?.optString("content", "").orEmpty()
                    history.add(JSONObject().put("role", "assistant").put("content", c))
                    return@withContext c
                }
                history.add(JSONObject().put("role", "assistant").put("content", content))
                return@withContext content
            }
        }
        "（工具调用超过上限，请重试）"
    }

    private fun post(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(LlamaServer.BASE_URL + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            val text = resp.body?.string() ?: "{}"
            return JSONObject(text)
        }
    }
}