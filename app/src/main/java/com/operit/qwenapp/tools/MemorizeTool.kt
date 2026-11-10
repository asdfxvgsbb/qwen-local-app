package com.operit.qwenapp.tools

import org.json.JSONObject
import java.io.File

/**
 * 【记忆工具】让 AI 记住用户偏好/备忘/事实。
 * 存储在 App 私有目录 notes.txt，action = remember|recall|forget。
 */
class MemorizeTool : Tool {
    override val name = "memorize"
    override val description = "记忆工具：记住用户偏好或备忘。action = remember|recall|forget，" +
        "key = 记忆名称（如 生日/名字/偏好），value = 内容（remember 时）。" +
        "用于'记一下我生日是X月X日'这类需求。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject()
                .put("type", "string")
                .put("enum", org.json.JSONArray()
                    .put("remember").put("recall").put("forget"))
                .put("description", "操作类型"))
            .put("key", JSONObject()
                .put("type", "string")
                .put("description", "记忆名称，如 生日/名字/偏好"))
            .put("value", JSONObject()
                .put("type", "string")
                .put("description", "要记住的内容（remember 时需要）"))
            .put("query", JSONObject()
                .put("type", "string")
                .put("description", "要回忆的关键词，如 生日"))
        )
        .put("required", org.json.JSONArray().put("action"))

    override fun execute(args: JSONObject): String {
        val action = args.optString("action", "")
        val key = args.optString("key", "").trim()
        val file = notesFile()
        return when (action) {
            "remember" -> {
                if (key.isEmpty()) return "错误：缺少 key"
                val value = args.optString("value", "").trim()
                if (value.isEmpty()) return "错误：缺少 value"
                val lines = readLines(file).toMutableList()
                lines.removeAll { it.startsWith(key + "=") } // 覆盖同名
                lines.add("$key=$value")
                file.writeText(lines.joinToString("\n"))
                "✅ 已记住：$key = $value"
            }
            "recall" -> {
                val q = (args.optString("query", "") + " " + key).trim()
                if (q.isEmpty()) return "错误：缺少 query 或 key"
                val lines = readLines(file)
                val hits = lines.filter { it.contains(q) || q.split(" ").any { w -> w.isNotEmpty() && it.contains(w) } }
                if (hits.isEmpty()) "📝 没有找到相关记忆（可以让我'记一下…'来添加）" else hits.joinToString("\n")
            }
            "forget" -> {
                if (key.isEmpty()) return "错误：缺少 key"
                val lines = readLines(file).toMutableList()
                val before = lines.size
                lines.removeAll { it.startsWith(key + "=") }
                file.writeText(lines.joinToString("\n"))
                if (lines.size == before) "❌ 没有找到 $key 的记忆" else "🗑 已删除 $key 的记忆"
            }
            else -> "不支持的操作: $action（支持 remember/recall/forget）"
        }
    }

    private fun notesFile(): File {
        val ctx = ToolRegistry.appContext
            ?: return File(System.getProperty("java.io.tmpdir", "."), "notes.txt")
        val dir = File(ctx.filesDir, "memory")
        dir.mkdirs()
        return File(dir, "notes.txt")
    }

    private fun readLines(f: File): List<String> {
        return try { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() } catch (e: Exception) { emptyList() }
    }
}