package com.operit.qwenapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：上下文长度 / 线程数 / 系统提示词 / 清空历史
 * 保存在 SharedPreferences，重启模型后生效
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "qwen_app"
        const val KEY_CTX = "ctx"
        const val KEY_THREADS = "threads"
        const val KEY_PROMPT = "prompt"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_WEB_SEARCH = "web_search_enabled"
        const val KEY_FAST_REPLY = "fast_reply"
        const val DEFAULT_WORKSPACE = "/sdcard/ai"

        fun getCtx(ctx: Context): Int =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CTX, 2048)
        fun getThreads(ctx: Context): Int =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THREADS, 4)
        fun getPrompt(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROMPT, "") ?: ""
        fun getWorkspace(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WORKSPACE, DEFAULT_WORKSPACE) ?: DEFAULT_WORKSPACE
        fun getWebSearchEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WEB_SEARCH, true)
        fun getFastReply(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FAST_REPLY, true)
    }

    private val REQ_PICK_DIR = 3001

    private lateinit var etWorkspace: EditText

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_DIR && resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            // 尝试用 DocumentsContract 拿真实路径；拿不到就显示 URI（SAF 目录）
            val path = runCatching {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":")
                if (split.size > 1) {
                    // primary:xxx -> /storage/emulated/0/xxx
                    if (split[0] == "primary") "/storage/emulated/0/${split[1]}"
                    else "/storage/emulated/0/${split[1]}"
                } else docId
            }.getOrDefault(uri.toString())
            etWorkspace.setText(path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etCtx = findViewById<EditText>(R.id.etCtx)
        val etThreads = findViewById<EditText>(R.id.etThreads)
        val etPrompt = findViewById<EditText>(R.id.etPrompt)
        etWorkspace = findViewById<EditText>(R.id.etWorkspace)
        val swWebSearch = findViewById<Switch>(R.id.swWebSearch)
        val swFastReply = findViewById<Switch>(R.id.swFastReply)
        val btnPickWorkspace = findViewById<Button>(R.id.btnPickWorkspace)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnClear = findViewById<Button>(R.id.btnClearHistory)

        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        etCtx.setText(sp.getInt(KEY_CTX, 2048).toString())
        etThreads.setText(sp.getInt(KEY_THREADS, 4).toString())
        etPrompt.setText(sp.getString(KEY_PROMPT, ""))
        etWorkspace.setText(sp.getString(KEY_WORKSPACE, DEFAULT_WORKSPACE))
        swWebSearch.isChecked = sp.getBoolean(KEY_WEB_SEARCH, true)
        swFastReply.isChecked = sp.getBoolean(KEY_FAST_REPLY, true)

        // 系统原生目录选择器：选中目录后自动填路径
        btnPickWorkspace.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_DIR)
        }

        btnSave.setOnClickListener {
            val ctx = etCtx.text.toString().toIntOrNull() ?: 2048
            val threads = etThreads.text.toString().toIntOrNull() ?: 4
            val prompt = etPrompt.text.toString().trim()
            val workspace = etWorkspace.text.toString().trim().ifEmpty { DEFAULT_WORKSPACE }
            sp.edit()
                .putInt(KEY_CTX, ctx.coerceIn(512, 8192))
                .putInt(KEY_THREADS, threads.coerceIn(1, 8))
                .putString(KEY_PROMPT, prompt)
                .putString(KEY_WORKSPACE, workspace)
                .putBoolean(KEY_WEB_SEARCH, swWebSearch.isChecked)
                .putBoolean(KEY_FAST_REPLY, swFastReply.isChecked)
                .apply()
            Toast.makeText(this, "已保存（重启模型后生效）", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnClear.setOnClickListener {
            // 清空聊天历史（通过静态 ChatClient.resetHistory 或重启 Activity）
            sp.edit().putString(KEY_PROMPT, "").apply()
            Toast.makeText(this, "提示：历史跟随会话，重启 App 自动清空", Toast.LENGTH_SHORT).show()
        }
    }
}