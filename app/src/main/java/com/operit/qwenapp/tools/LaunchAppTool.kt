package com.operit.qwenapp.tools

import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/** 打开应用工具：根据用户说的名称（如微信/QQ/抖音）在已安装应用里匹配并启动 */
class LaunchAppTool : Tool {
    override val name = "launch_app"
    override val description = "打开手机上已安装的应用。参数 name = 应用名称（中文或英文，如 微信/QQ/抖音/支付宝/相机），会在已安装应用里匹配并启动。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "要打开的应用名，如 微信、QQ、抖音"))
        )
        .put("required", org.json.JSONArray().put("name"))

    override fun execute(args: JSONObject): String {
        val want = args.optString("name", "").trim().lowercase()
        if (want.isBlank()) return "错误：缺少 name 参数（要打开哪个应用？）"
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        val pm = ctx.packageManager
        val apps = try { pm.getInstalledApplications(PackageManager.GET_META_DATA) } catch (e: Exception) { emptyList() }
        if (apps.isEmpty()) return "无法读取已安装应用列表"

        // 先精确匹配 label，再包含匹配
        var best: android.content.pm.ApplicationInfo? = null
        var bestLabel = ""
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            if (!bestLabel.isEmpty() && label.lowercase() == want) {
                best = app; bestLabel = label; break
            }
        }
        if (best == null) {
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.lowercase().contains(want)) {
                    best = app; bestLabel = label; break
                }
            }
        }
        if (best == null) {
            val names = apps.mapNotNull { pm.getApplicationLabel(it).toString() }
                .sorted().filter { it.isNotBlank() }
            val sample = names.take(40).joinToString("、")
            return "未找到应用「$want」。已安装的部分应用：$sample"
        }
        val launch = pm.getLaunchIntentForPackage(best.packageName)
            ?: return "应用「$bestLabel」无法直接启动（无启动入口）"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(launch)
            return "✅ 已打开应用「$bestLabel」"
        } catch (e: Exception) {
            return "打开「$bestLabel」失败: ${e.message}"
        }
    }
}