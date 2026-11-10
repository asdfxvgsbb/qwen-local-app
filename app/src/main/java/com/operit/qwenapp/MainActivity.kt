package com.operit.qwenapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：聊天 + 一键启动本地模型
 * 打开 App 自动检测服务，未运行则提示一键启动
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var btnSend: Button
    private lateinit var status: TextView
    private lateinit var btnPickModel: Button
    private lateinit var btnStart: Button
    private lateinit var btnSettings: TextView

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private val client = ChatClient()

    private val REQ_PICK_MODEL = 1001
    private val REQ_PERMISSION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerChat)
        input = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        status = findViewById(R.id.serverStatus)
        btnPickModel = findViewById(R.id.btnPickModel)
        btnStart = findViewById(R.id.btnStart)
        btnSettings = findViewById(R.id.btnSettings)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ChatAdapter(messages)
        recycler.adapter = adapter

        // 检查服务状态（异步）
        checkServer()

        btnSend.setOnClickListener { send() }
        btnPickModel.setOnClickListener { pickModel() }
        btnStart.setOnClickListener { startServer() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 动态申请存储权限（Android 6+ 需要，targetSdk 34 需要 MANAGE 或 READ）
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: MANAGE_EXTERNAL_STORAGE 需要跳设置页，这里用 SAF 选择器替代，不强求
            return
        }
        if (Build.VERSION.SDK_INT >= 23) {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_PERMISSION)
            }
        }
    }

    /** 打开系统文件选择器选 .gguf 模型 */
    private fun pickModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
        }
        startActivityForResult(intent, REQ_PICK_MODEL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            status.text = "正在导入模型…"
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) { LlamaServer.importModelFromUri(this@MainActivity, uri) }
                if (ok) {
                    status.text = "模型已导入，可启动"
                    Toast.makeText(this@MainActivity, "模型导入成功 ✅", Toast.LENGTH_LONG).show()
                } else {
                    status.text = getString(R.string.server_stopped)
                    Toast.makeText(this@MainActivity, "导入失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkServer() {
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) { LlamaServer.isHealthy() }
            if (ok) {
                status.text = getString(R.string.server_running)
                status.setTextColor(0xFF4CAF50.toInt())
            } else {
                status.text = getString(R.string.server_stopped)
                status.setTextColor(0xFFFF9800.toInt())
                promptStart()
            }
        }
    }

    /** 询问是否一键启动模型 */
    private fun promptStart() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("启动本地模型")
            .setMessage("检测到本地模型未运行。是否启动？\n首次启动需解压程序，约需10秒；模型加载约10秒。\n\n需要模型文件：\n${LlamaServer.getModelFile(this).absolutePath}\n（可用下方\"选择模型文件\"导入，或确认默认目录已有模型）")
            .setPositiveButton("启动") { _, _ -> startServer() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startServer() {
        status.text = "正在启动模型…"
        status.setTextColor(0xFFFF9800.toInt())
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) { LlamaServer.start(this@MainActivity) }
            if (ok) {
                status.text = getString(R.string.server_running)
                status.setTextColor(0xFF4CAF50.toInt())
                Toast.makeText(this@MainActivity, "模型启动成功 ✅", Toast.LENGTH_SHORT).show()
                addMessage(ChatMessage("assistant", "你好！我是运行在你手机上的本地AI。我可以：\n• 普通聊天\n• 精确计算\n• 查天气\n• 查时间\n• 执行代码（JavaScript）\n• 读写文件\n直接问我就行！"))
            } else {
                status.text = getString(R.string.server_stopped)
                status.setTextColor(0xFFFF9800.toInt())
                Toast.makeText(this@MainActivity, "启动失败，请先选择模型文件或确认模型存在", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addMessage(ChatMessage("user", text))

        // 加一个"思考中"占位
        val thinkingIdx = messages.size
        addMessage(ChatMessage("assistant", "思考中…"))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 健康检查必须在 IO 线程（主线程禁止网络请求，否则抛 NetworkOnMainThreadException）
                val healthy = withContext(Dispatchers.IO) { LlamaServer.isHealthy() }
                if (!healthy) {
                    messages[thinkingIdx] = ChatMessage("assistant", "⚠️ 模型未运行，请先点「启动模型」")
                    adapter.notifyItemChanged(thinkingIdx)
                    Toast.makeText(this@MainActivity, "模型未运行，请先启动", Toast.LENGTH_SHORT).show()
                    promptStart()
                    return@launch
                }
                // 计时反馈：等待首字期间每秒刷新"思考中…(X秒)"，避免看起来像卡死
                var waited = 0
                // 工具调用后回答另起一条消息（避免覆盖工具卡片）
                var isToolCard = false
                var answerIdx = thinkingIdx
                val ticker = kotlinx.coroutines.Job()
                val tickJob = launch {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        waited++
                        val cur = messages.getOrNull(thinkingIdx)?.content.orEmpty()
                        if (cur == "思考中…" || cur.startsWith("思考中…(")) {
                            messages[thinkingIdx] = ChatMessage("assistant", "思考中…(${waited}s)")
                            adapter.notifyItemChanged(thinkingIdx)
                        }
                    }
                }
                // 流式：逐字刷新"思考中…"气泡为真实回复
                val reply = try {
                    client.chatStream(
                        text,
                        onToolCall = { name, args, result, running ->
                            runOnUiThread {
                                // 工具调用时先停掉计时器，显示工具卡片
                                tickJob.cancel()
                                val first = !isToolCard
                                isToolCard = true
                                messages[thinkingIdx] = ChatMessage(
                                    "assistant", "",
                                    isToolResult = true,
                                    toolName = name,
                                    toolArgs = args,
                                    toolResult = result,
                                    toolRunning = running
                                )
                                adapter.notifyItemChanged(thinkingIdx)
                                if (first) {
                                    // 首次回调：建回答占位（等工具出结果后流式填充）
                                    answerIdx = messages.size
                                    addMessage(ChatMessage("assistant", "思考中…"))
                                }
                            }
                        },
                        onDelta = { delta ->
                            runOnUiThread {
                                tickJob.cancel()
                                val cur = messages.getOrNull(answerIdx)?.content.orEmpty()
                                // 如果还是"思考中…"占位，直接替换；否则追加
                                val base = if (cur.startsWith("思考中")) "" else cur
                                if (answerIdx >= messages.size) {
                                    addMessage(ChatMessage("assistant", delta))
                                } else {
                                    messages[answerIdx] = ChatMessage("assistant", base + delta)
                                    adapter.notifyItemChanged(answerIdx)
                                }
                                recycler.scrollToPosition(messages.size - 1)
                            }
                        }
                    )
                } finally {
                    tickJob.cancel()
                }
                // 确保最终显示完整回复
                runOnUiThread {
                    if (reply.isNotEmpty()) {
                        if (isToolCard && answerIdx < messages.size) {
                            messages[answerIdx] = ChatMessage("assistant", reply)
                            adapter.notifyItemChanged(answerIdx)
                        } else {
                            messages[thinkingIdx] = ChatMessage("assistant", reply)
                            adapter.notifyItemChanged(thinkingIdx)
                        }
                    }
                    recycler.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                // 出现异常时把"思考中…(Xs)"替换为错误信息
                messages[thinkingIdx] = ChatMessage("assistant", "出错: ${e.message}")
                adapter.notifyItemChanged(thinkingIdx)
            }
        }
    }

    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
    }
}