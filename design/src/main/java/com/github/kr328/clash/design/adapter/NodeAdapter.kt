package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterNodeBinding

class NodeAdapter(
    private val selectedColor: Int,
    private val selectedBackgroundColor: Int,
    private val onSelect: (String) -> Unit,
) : RecyclerView.Adapter<NodeAdapter.Holder>() {

    class Holder(val binding: AdapterNodeBinding) :
        RecyclerView.ViewHolder(binding.root)

    private val nodes = mutableListOf<Proxy>()
    private var selected = ""

    fun submit(list: List<Proxy>, selectedName: String) {
        nodes.clear()
        nodes.addAll(list)

        selected = selectedName

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterNodeBinding
                .inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val proxy = nodes[position]
        val context = holder.itemView.context
        val binding = holder.binding

        binding.nodeNameView.text = proxy.title.ifBlank { proxy.name }
        binding.nodeSubView.text = if (proxy.isGroup)
            context.getString(R.string.node_group_tag)
        else
            proxy.subtitle.ifBlank { proxy.type }

        binding.nodeDelayView.text = delayText(context, proxy.delay)
        binding.nodeDelayView.setTextColor(delayColor(proxy.delay))

        val isSelected = proxy.name == selected

        binding.nodeCheckView.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        binding.nodeCheckView.imageTintList = ColorStateList.valueOf(selectedColor)
        binding.root.backgroundTintList =
            if (isSelected) ColorStateList.valueOf(selectedBackgroundColor) else null

        binding.root.setOnClickListener { onSelect(proxy.name) }
    }

    override fun getItemCount(): Int {
        return nodes.size
    }

    private fun delayText(context: Context, delay: Int): CharSequence = when {
        delay in 1 until MAX_DELAY -> context.getString(R.string.node_delay_ms, delay)
        delay == 0 -> context.getString(R.string.node_delay_untested)
        else -> context.getString(R.string.node_delay_timeout)
    }

    private fun delayColor(delay: Int): Int = when {
        delay in 1..200 -> COLOR_FAST
        delay in 201..500 -> COLOR_MEDIUM
        delay in 501..2000 -> COLOR_OK
        delay in 2001 until MAX_DELAY -> COLOR_SLOW
        delay == 0 -> COLOR_IDLE
        else -> COLOR_SLOW
    }

    private companion object {
        const val MAX_DELAY = 0xffff   // mihomo 用 uint16 最大值表示超时/不可用

        val COLOR_FAST = 0xFF2E7D32.toInt()
        val COLOR_MEDIUM = 0xFFEF6C00.toInt()
        val COLOR_OK = 0xFFF9A825.toInt()
        val COLOR_SLOW = 0xFFC62828.toInt()
        val COLOR_IDLE = 0xFF9E9E9E.toInt()
    }
}