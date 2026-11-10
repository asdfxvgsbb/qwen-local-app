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
            "memorize" to "记忆"
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
    private val TOOL_KEYWORDS = mapOf(
        "calculator" to listOf("计算", "算一下", "数学", "加减乘除", "等于", "乘以", "除以"),
        "get_weather" to listOf("天气", "下雨", "台风", "气温", "几度"),
        "get_current_time" to listOf("时间", "几点", "日期", "星期", "现在"),
        "run_code" to listOf("代码", "脚本", "编程", "javascript", "python", "执行代码"),
        "file_ops" to listOf("文件", "读写", "保存", "读取", "打开", "写入"),
        "run_terminal" to listOf("终端", "命令", "ls", "cat ", "pwd", "echo ", "shell"),
        "device_info" to listOf("设备", "电池", "电量", "存储", "内存", "空间", "还剩", "多大"),
        "unit_convert" to listOf("换算", "单位", "多少米", "多少公里", "多少斤", "多少克", "摄氏度", "华氏", "kb", "mb", "gb"),
        "memorize" to listOf("记住", "记一下", "记忆", "备忘录", "别忘", "我的生日", "我叫")
    )

    /** 根据用户消息返回需要带的工具名列表（未命中则空 = 不带工具直接聊天） */
    private fun selectedTools(userText: String): List<String> {
        val t = userText.lowercase()
        return TOOL_KEYWORDS.filter { (_, kws) -> kws.any { t.contains(it) } }.keys.toList()
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
            // run_code / file_ops / run_terminal / unit_convert / memorize 参数复杂，不强行调用
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
                if (looksLikeToolJson) {
                    toolNames = emptyList()
                    // 注意：此时 assistant 消息尚未加入 history（在 return 前才 add），
                    // 所以这里只需把 toolNames 置空重发一轮，用户消息保留
                    continue
                }
                // 强制工具适配：模型没能力调工具时，由 App 层直接执行命中的工具，
                // 把真实结果喂回，再让模型基于结果总结（适配任何无 tool calling 能力的模型）
                // 注意：forceRunTools 内部已加入 assistant(带tool_calls)+tool 消息，这里不要再 add
                if (toolNames.isNotEmpty() && forceRunTools(toolNames, userText, onToolCall)) {
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
                                sb.append(content)
                                onDelta(content)
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
                // 强制工具适配：模型没调工具时，由 App 层直接执行命中的工具并喂回
                // forceRunTools 内部已加入 assistant(带tool_calls)+tool 消息，这里不要再 add
                if (toolNames.isNotEmpty() && forceRunTools(toolNames, userText, onToolCall)) {
                    toolNames = emptyList()
                    continue
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