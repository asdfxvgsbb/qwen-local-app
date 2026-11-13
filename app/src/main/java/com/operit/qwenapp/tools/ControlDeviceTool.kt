package com.operit.qwenapp.tools

import org.json.JSONObject

/**
 * 手机操控工具（v9.21，需 Shizuku 授权）：
 * 以系统 shell 权限模拟 点击 / 滑动 / 输入 / 按键 / 截图 / 打开应用。
 * 未装 Shizuku 或未授权时返回引导提示，不影响其他工具。
 */
class ControlDeviceTool : Tool {
    override val name = "control_device"
    override val description = "操控手机（需 Shizuku 授权）：模拟点击、滑动、输入、按键、截图、打开应用。" +
        "action 为 tap(点击 x,y) / swipe(滑动 x1,y1,x2,y2,时长ms) / text(输入文字) / key(按键 back|home|enter|volume_up|volume_down) / " +
        "screencap(截图保存) / open(打开应用包名或名称)。如「点击屏幕 500 300」「滑动 500 900 到 500 300」「返回键」「打开微信」「截图」。"

    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "操作类型：tap/swipe/text/key/screencap/open"))
            .put("x", JSONObject().put("type", "integer").put("description", "点击/起点 x 坐标"))
            .put("y", JSONObject().put("type", "integer").put("description", "点击/起点 y 坐标"))
            .put("x2", JSONObject().put("type", "integer").put("description", "滑动终点 x 坐标"))
            .put("y2", JSONObject().put("type", "integer").put("description", "滑动终点 y 坐标"))
            .put("duration", JSONObject().put("type", "integer").put("description", "滑动时长毫秒，默认300"))
            .put("text", JSONObject().put("type", "string").put("description", "输入的文字（仅 ASCII 可靠）"))
            .put("key", JSONObject().put("type", "string").put("description", "按键名：back/home/enter/volume_up/volume_down"))
            .put("app", JSONObject().put("type", "string").put("description", "要打开的应用：包名或中文名（如 com.tencent.mm 或 微信）"))
        )
        .put("required", org.json.JSONArray().put("action"))

    override fun execute(args: JSONObject): String {
        // 检查 Shizuku
        if (!ShizukuShell.isAvailable()) {
            // 没装 → 提示装；装了没授权 → 引导授权
            return if (ShizukuShell.isInstalled()) {
                val ctx = ToolRegistry.appContext
                val act = ToolRegistry.currentActivity
                if (ctx != null && act != null && ShizukuShell.requestPermission(act)) {
                    "需要先在 Shizuku 弹窗里点「允许」授权本应用，然后再说一次要做的操作"
                } else {
                    "Shizuku 未授权：请打开 Shizuku App 给本应用授权后重试"
                }
            } else {
                "未检测到 Shizuku：操控手机需要 Shizuku（用 adb 或 root 启动）。\n" +
                    "未装 Shizuku 时终端仍可用内置 Linux 环境（权限受限）。"
            }
        }

        val action = args.optString("action", "").lowercase()
        return when (action) {
            "tap" -> {
                val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                if (x < 0 || y < 0) "错误：tap 需要 x,y 坐标"
                else ShizukuShell.exec("input tap $x $y").let { (c, o) -> if (c == 0) "✅ 已点击 ($x, $y)" else "点击失败: $o" }
            }
            "swipe" -> {
                val x1 = args.optInt("x", -1); val y1 = args.optInt("y", -1)
                val x2 = args.optInt("x2", -1); val y2 = args.optInt("y2", -1)
                val dur = args.optInt("duration", 300)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) "错误：swipe 需要 x,y,x2,y2"
                else ShizukuShell.exec("input swipe $x1 $y1 $x2 $y2 $dur").let { (c, o) -> if (c == 0) "✅ 已滑动 ($x1,$y1) → ($x2,$y2)" else "滑动失败: $o" }
            }
            "key" -> {
                val key = args.optString("key", "").lowercase()
                val code = when (key) {
                    "back", "返回", "返回键" -> 4
                    "home", "主页" -> 3
                    "enter", "回车", "确定" -> 66
                    "volume_up", "音量加" -> 24
                    "volume_down", "音量减" -> 25
                    "power", "电源" -> 26
                    "menu", "菜单" -> 82
                    else -> -1
                }
                if (code < 0) "错误：不支持的按键 $key（支持 back/home/enter/volume_up/volume_down/power/menu）"
                else ShizukuShell.exec("input keyevent $code").let { (c, o) -> if (c == 0) "✅ 已按键: $key" else "按键失败: $o" }
            }
            "text" -> {
                val text = args.optString("text", "")
                if (text.isEmpty()) "错误：text 需要输入内容"
                else ShizukuShell.inputText(text).let { (c, o) -> if (c == 0) "✅ 已输入: $text" else o }
            }
            "screencap" -> {
                // 截图保存到 /sdcard/Download（shell 用户有写权限）
                val path = "/sdcard/Download/screencap_${System.currentTimeMillis()}.png"
                val ctx = ToolRegistry.appContext
                // 截图前：隐藏悬浮窗长条，显示小亮点（避免截到悬浮窗）
                if (ctx != null) FloatWindowManager.hideForScreenshot(ctx)
                try { Thread.sleep(300) } catch (_: Exception) {}
                val (c, o) = ShizukuShell.exec("screencap -p $path")
                // 截图后：恢复长条，移除亮点
                if (ctx != null) FloatWindowManager.restoreAfterScreenshot(ctx)
                if (c == 0) "✅ 截图已保存: $path（可用 read_file 读取）" else "截图失败: $o"
            }
            "open" -> {
                val app = args.optString("app", "")
                if (app.isEmpty()) "错误：open 需要应用名或包名"
                else openApp(app).let { if (it.startsWith("✅")) it else "打开失败: $it" }
            }
            else -> "错误：未知 action $action（支持 tap/swipe/text/key/screencap/open）"
        }
    }

    /** 打开应用：支持 包名 / 常见中文名映射 / 查不到就 am start 尝试 */
    private fun openApp(app: String): String {
        val known = mapOf(
            "微信" to "com.tencent.mm", "QQ" to "com.tencent.mobileqq", "抖音" to "com.ss.android.ugc.aweme",
            "支付宝" to "com.eg.android.AlipayGphone", "淘宝" to "com.taobao.taobao", "京东" to "com.jingdong.app.mall",
            "微博" to "com.sina.weibo", "B站" to "tv.danmaku.bili", "哔哩哔哩" to "tv.danmaku.bili",
            "小红书" to "com.xingin.xhs", "百度" to "com.baidu.searchbox", "高德" to "com.autonavi.minimap",
            "美团" to "com.sankuai.meituan", "浏览器" to "com.android.browser", "相机" to "com.android.camera",
            "设置" to "com.android.settings", "相册" to "com.android.gallery3d", "文件管理" to "com.android.documentsui"
        )
        val pkg = known[app] ?: if (app.contains(".")) app else null
        if (pkg == null) {
            // 尝试用 pm list packages 模糊搜包名
            val (c, o) = ShizukuShell.exec("pm list packages | grep -i ${app.lowercase()} | head -3")
            return if (c == 0 && o.isNotBlank()) {
                val line = o.lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
                if (line != null) launchPkg(line) else "未找到应用 $app：可用包名如 com.tencent.mm"
            } else "未找到应用 $app：可用包名如 com.tencent.mm"
        }
        return launchPkg(pkg)
    }

    private fun launchPkg(pkg: String): String {
        val (c, o) = ShizukuShell.exec("am start -n ${pkg}/.MainActivity 2>/dev/null || am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg")
        return if (c == 0 && !o.contains("Error")) "✅ 已打开 $pkg" else "打开失败: $o"
    }
}