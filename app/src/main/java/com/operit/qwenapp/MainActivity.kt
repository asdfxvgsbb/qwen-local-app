package com.operit.qwenapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private val client = ChatClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerChat)
        input = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        status = findViewById(R.id.serverStatus)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ChatAdapter(messages)
        recycler.adapter = adapter

        // 检查服务状态（异步）
        checkServer()

        btnSend.setOnClickListener { send() }
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
            .setMessage("检测到本地模型未运行。是否启动？\n首次启动需解压程序，约需10秒；模型加载约10秒。\n\n需要模型文件：\n${LlamaServer.MODEL_FILE}\n（需已下载 Qwen2.5-1.5B-tools-Q5_K_M.gguf）")
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
                Toast.makeText(this@MainActivity, "启动失败，请确认模型文件存在", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!LlamaServer.isHealthy()) {
            Toast.makeText(this, "模型未运行，请先启动", Toast.LENGTH_SHORT).show()
            promptStart()
            return
        }
        input.setText("")
        addMessage(ChatMessage("user", text))

        // 加一个"思考中"占位
        val thinkingIdx = messages.size
        addMessage(ChatMessage("assistant", "思考中…"))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val reply = client.chat(text) { toolInfo ->
                    // 工具调用过程更新（主线程）
                    runOnUiThread {
                        messages[thinkingIdx] = ChatMessage("assistant", toolInfo, isToolResult = true)
                        adapter.notifyItemChanged(thinkingIdx)
                    }
                }
                messages[thinkingIdx] = ChatMessage("assistant", reply)
                adapter.notifyItemChanged(thinkingIdx)
                recycler.scrollToPosition(messages.size - 1)
            } catch (e: Exception) {
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