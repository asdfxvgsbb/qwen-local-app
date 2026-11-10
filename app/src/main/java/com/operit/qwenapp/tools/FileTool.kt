package com.operit.qwenapp.tools

import org.json.JSONObject
import java.io.File

/** 文件工具：读写文本文件（限定在可配置的工作区内，默认 /sdcard/ai，安全） */
class FileTool : Tool {
    override val name = "file_ops"
    override val description = "读写文本文件。action = read|write，path = 文件路径（需在工作区目录下），content = 写入内容（write 时）"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", org.json.JSONArray().put("read").put("write")))
            .put("path", JSONObject().put("type", "string").put("description", "文件路径，如 /sdcard/ai/notes.txt"))
            .put("content", JSONObject().put("type", "string").put("description", "写入内容")))
        .put("required", org.json.JSONArray().put("action").put("path"))

    /** 当前工作区：可在设置里修改，默认 /sdcard/ai */
    private fun workspace(): String {
        val ctx = ToolRegistry.appContext
        return ctx?.let { com.operit.qwenapp.SettingsActivity.getWorkspace(it) } ?: "/sdcard/ai"
    }

    override fun execute(args: JSONObject): String {
        val action = args.optString("action", "read")
        val path = args.optString("path", "")
        val ws = workspace()
        if (path.isBlank()) return "错误：缺少 path 参数"
        if (path != ws && !path.startsWith(ws + "/")) return "错误：路径必须在工作区 $ws 下"
        return try {
            when (action) {
                "read" -> {
                    val f = File(path)
                    if (!f.exists()) "错误：文件不存在 $path"
                    else {
                        val content = f.readText()
                        if (content.length > 2000) content.take(2000) + "\n...(截断，共${content.length}字符)" else content
                    }
                }
                "write" -> {
                    val f = File(path)
                    f.parentFile?.mkdirs()
                    f.writeText(args.optString("content", ""))
                    "已写入 $path (${f.length()} 字节)"
                }
                else -> "错误：未知 action $action"
            }
        } catch (e: Exception) {
            "文件操作失败: ${e.message}"
        }
    }
}