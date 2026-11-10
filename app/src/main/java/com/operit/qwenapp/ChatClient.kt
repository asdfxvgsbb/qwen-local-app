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

    private val SYSTEM_PROMPT =
        "你是运行在手机上的本地AI助手，基于Qwen2.5。你可以调用工具来完成任务。" +
        "如果用户需要计算、查天气、查时间、执行代码或读写文件，请调用对应工具。" +
        "工具调用结果会以 tool 消息返回给你，请基于结果组织最终回答。"

    /** 消息历史（OpenAI 格式） */
    private val history = mutableListOf<JSONObject>()

    fun resetHistory() {
        history.clear()
    }

    /**
     * 发送用户消息，返回模型最终回复文本
     * onToolCall: 回调工具调用信息（用于显示"正在调用工具..."）
     */
    suspend fun chat(
        userText: String,
        onToolCall: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        history.add(JSONObject().put("role", "user").put("content", userText))

        // 工具调用循环，最多 5 轮
        repeat(5) { round ->
            val body = buildBody(stream = false)

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

                    onToolCall("调用工具: $name")
                    // 本地执行工具
                    val result = ToolRegistry.execute(name, args)
                    onToolCall("✅ $name → $result")

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
                history.add(msg)
                return@withContext content
            }
        }
        "（工具调用超过上限，请重试）"
    }

    private fun buildBody(stream: Boolean): JSONObject {
        return JSONObject()
            .put("model", "local-qwen")   // 模型名，服务器不校验
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                history.forEach { put(it) }
            })
            .put("tools", ToolRegistry.toToolsJson())
            .put("tool_choice", "auto")
            .put("stream", stream)
            .put("max_tokens", 512)
    }

    /**
     * 流式版本：逐字回调 onDelta，直到完整回复。
     * 兼容流式分片中的工具调用（llama-server / OpenAI 格式），
     * 工具调用完成后自动把结果喂回并继续流式输出最终回答。
     */
    suspend fun chatStream(
        userText: String,
        onToolCall: (String) -> Unit,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        history.add(JSONObject().put("role", "user").put("content", userText))

        repeat(5) { round ->
            val body = buildBody(stream = true)
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
                            val content = delta.optString("content", "")
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
                    onToolCall("调用工具: $name")
                    val result = try {
                        ToolRegistry.execute(name, args)
                    } catch (e: Exception) {
                        "工具执行错误: ${e.message}"
                    }
                    onToolCall("✅ $name → ${result.take(120)}")
                    history.add(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result))
                }
                // 继续下一轮流式输出最终回答
            } else {
                // 纯文本回答结束
                val content = sb.toString()
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