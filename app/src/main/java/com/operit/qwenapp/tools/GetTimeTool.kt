package com.operit.qwenapp.tools

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 获取当前日期时间 */
class GetTimeTool : Tool {
    override val name = "get_current_time"
    override val description = "获取当前日期和时间。不需要参数。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", org.json.JSONArray())

    override fun execute(args: JSONObject): String {
        val fmt = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.CHINESE)
        return fmt.format(Date())
    }
}