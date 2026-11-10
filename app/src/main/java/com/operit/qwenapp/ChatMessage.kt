package com.operit.qwenapp

/** 聊天消息数据类 */
data class ChatMessage(
    val role: String,      // "user" / "assistant" / "system"
    val content: String,
    val isToolResult: Boolean = false,
    val toolName: String? = null,   // 工具调用时显示工具名
    val toolArgs: String = "",      // 工具参数（展开详情用）
    val toolResult: String = "",    // 工具结果（展开详情用）
    val toolRunning: Boolean = false // 是否调用中
)