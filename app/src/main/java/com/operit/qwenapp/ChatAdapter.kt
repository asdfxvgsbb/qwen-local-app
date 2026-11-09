package com.operit.qwenapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 聊天气泡适配器 */
class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.bubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.bubble.text = msg.content
        val lp = holder.bubble.layoutParams as ViewGroup.MarginLayoutParams

        if (msg.role == "user") {
            // 用户消息：右对齐，蓝底
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_user)
            lp.marginStart = dp(holder, 80)
            lp.marginEnd = dp(holder, 8)
            holder.bubble.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        } else {
            // AI消息：左对齐，灰底（工具结果用浅绿底）
            holder.bubble.setBackgroundResource(
                if (msg.isToolResult) R.drawable.bg_bubble_tool else R.drawable.bg_bubble_ai
            )
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