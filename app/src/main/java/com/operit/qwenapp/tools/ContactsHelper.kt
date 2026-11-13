package com.operit.qwenapp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONObject

/**
 * 通讯录助手：把「名字」解析成电话号码。
 * 供 打电话/发短信 工具使用（如「打电话给妈妈」→ 自动查通讯录找妈妈）。
 * 需要 READ_CONTACTS 权限，未授权时引导跳转设置页。
 */
object ContactsHelper {

    /** 是否已有通讯录读取权限 */
    fun hasPermission(ctx: Context): Boolean {
        return try {
            ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
    }

    /** 引导跳转到应用详情页开启权限 */
    fun guideToSettings(ctx: Context): String {
        val go = ToolRegistry.confirm(
            "需要通讯录权限",
            "从通讯录匹配联系人（如「打电话给妈妈」）需要读取通讯录。\n\n点击「去授权」跳转系统设置开启「通讯录」权限，然后返回重试。"
        )
        if (!go) return "已取消（未授权时无法读取通讯录）"
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "已为你打开应用设置页，请开启「通讯录」权限后，再叫我打电话/发短信"
        } catch (e: Exception) {
            "无法打开设置页：${e.message}。请到 系统设置→应用→本地 Agent→权限 开启「通讯录」"
        }
    }

    /**
     * 按名字查号码。name 可以是「妈妈」「张伟」等，模糊匹配。
     * @return 命中返回号码（多个取第一个），否则 null
     */
    fun resolveNameToNumber(ctx: Context, name: String): String? {
        if (!hasPermission(ctx)) return null
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val cname = cursor.getString(0)?.lowercase() ?: continue
                    val cnum = cursor.getString(1)?.replace(Regex("[\\s\\-()]"), "") ?: continue
                    if (cnum.isEmpty()) continue
                    // 模糊匹配：包含关系（如"妈妈"命中"妈妈"、"我的妈妈"）
                    if (wanted in cname || cname in wanted) {
                        return@use cnum
                    }
                    // 匹配"阿妈/老妈/妈"这类称呼
                    if (wanted.length <= 4 && cname.contains(wanted)) {
                        return@use cnum
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 列出通讯录里包含关键词的联系人（供 lookup_contact 工具用） */
    fun searchContacts(ctx: Context, keyword: String): List<Pair<String, String>> {
        if (!hasPermission(ctx)) return emptyList()
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && result.size < 20) {
                    val cname = cursor.getString(0) ?: continue
                    val cnum = cursor.getString(1)?.replace(Regex("[\\s\\-()]"), "") ?: continue
                    if (cname.lowercase().contains(kw) || kw in cname.lowercase()) {
                        result.add(cname to cnum)
                    }
                }
            }
        } catch (e: Exception) {
            // 权限异常等
        }
        return result
    }
}