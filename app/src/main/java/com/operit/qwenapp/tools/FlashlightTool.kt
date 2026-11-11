package com.operit.qwenapp.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONObject

/** 手电筒工具：打开/关闭闪光灯 */
class FlashlightTool : Tool {
    override val name = "flashlight"
    override val description = "打开/关闭手电筒（闪光灯）。参数 on = true/false。如「打开手电筒」。"
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("on", JSONObject().put("type", "boolean").put("description", "true=打开手电筒，false=关闭"))
        )
        .put("required", org.json.JSONArray().put("on"))

    override fun execute(args: JSONObject): String {
        val on = args.optBoolean("on", true)
        val ctx = ToolRegistry.appContext ?: return "错误：应用上下文未初始化"
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // 找有闪光灯的后置摄像头
            var torchId: String? = null
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id; break
                }
            }
            if (torchId == null) {
                // 退而求其次：任意带闪光灯的
                for (id in cm.cameraIdList) {
                    if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        torchId = id; break
                    }
                }
            }
            if (torchId == null) return "此设备没有闪光灯"
            cm.setTorchMode(torchId, on)
            if (on) "🔦 手电筒已打开" else "🔦 手电筒已关闭"
        } catch (e: SecurityException) {
            "需要相机权限才能使用手电筒：请到 设置→应用→权限 里授权相机"
        } catch (e: Exception) {
            "手电筒操作失败: ${e.message}"
        }
    }
}