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
                "你是本地AI助手。日常问答直接回答，不要用工具。" +
                "只有用户要求计算/天气/时间/代码/文件时才用工具。" +
                "工具结果会返回给你，基于结果回答。"
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
                // 正常文本回答
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

        repeat(5) { round ->
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