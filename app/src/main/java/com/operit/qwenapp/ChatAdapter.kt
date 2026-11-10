package com.operit.qwenapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 聊天气泡适配器：支持普通气泡 + 工具调用卡片（小卡片、显示工具名、点击展开详情） */
class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    /** 记录每个工具卡片是否已展开详情 */
    private val expanded = mutableSetOf<Int>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val toolCard: LinearLayout = view.findViewById(R.id.toolCard)
        val tvToolTitle: TextView = view.findViewById(R.id.tvToolTitle)
        val tvToolDetail: TextView = view.findViewById(R.id.tvToolDetail)
        val bubble: TextView = view.findViewById(R.id.bubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]

        // 工具卡片消息：显示小卡片，标题三态，点击展开/收起详情
        if (msg.toolName != null) {
            holder.bubble.visibility = View.GONE
            holder.toolCard.visibility = View.VISIBLE

            // 三态标题
            holder.tvToolTitle.text = when {
                msg.toolRunning -> "⏳ ${msg.toolName} 运行中…"
                msg.toolResult.isNotEmpty() -> "✅ ${msg.toolName}"
                else -> "🔧 ${msg.toolName}"
            }

            // 详情 = 参数 + 结果
            val detail = buildString {
                if (msg.toolArgs.isNotBlank() && msg.toolArgs != "{}") {
                    append("参数：").append(msg.toolArgs).append("\n")
                }
                if (msg.toolResult.isNotEmpty()) {
                    append("结果：").append(msg.toolResult)
                }
            }
            holder.tvToolDetail.text = detail.trim()

            // 展开/收起
            val isExpanded = expanded.contains(position)
            holder.tvToolDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.toolCard.setOnClickListener {
                if (isExpanded) expanded.remove(position) else expanded.add(position)
                notifyItemChanged(position)
            }
            return
        }

        // 普通消息：气泡
        holder.toolCard.visibility = View.GONE
        holder.bubble.visibility = View.VISIBLE
        holder.bubble.text = msg.content
        val lp = holder.bubble.layoutParams as ViewGroup.MarginLayoutParams

        if (msg.role == "user") {
            // 用户消息：右对齐
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_user)
            lp.marginStart = dp(holder, 80)
            lp.marginEnd = dp(holder, 8)
            holder.bubble.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        } else {
            // AI消息：左对齐
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_ai)
            lp.marginStart = dp(holder, 8)
            lp.marginEnd = dp(holder, 80)
            holder.bubble.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        holder.bubble.layoutParams = lp
    }

    override fun getItemCount(): Int = items.size

    private fun dp(holder: VH, v: Int): Int {
        return (v * holder.itemView.context.resources.displayMetrics.density).toInt()
    }
}