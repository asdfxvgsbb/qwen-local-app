package com.operit.qwenapp

/** 聊天消息数据类 */
data class ChatMessage(
    val role: String,      // "user" / "assistant" / "system"
    val content: String,
    val isToolResult: Boolean = false
)