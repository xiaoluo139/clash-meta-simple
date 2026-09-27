package com.github.kr328.clash.design.adapter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterCustomRuleBinding

class CustomRuleAdapter(
    private val describe: (String) -> CharSequence,
    private val onDelete: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<CustomRuleAdapter.Holder>() {

    class Holder(val binding: AdapterCustomRuleBinding) :
        RecyclerView.ViewHolder(binding.root)

    val rules = mutableListOf<String>()
    private var hitCounts: Map<String, Long> = emptyMap()

    fun submit(list: List<String>) {
        rules.clear()
        rules.addAll(list)

        notifyDataSetChanged()
    }

    fun setHitCounts(counts: Map<String, Long>) {
        hitCounts = counts

        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in rules.indices || to !in rules.indices)
            return

        val item = rules.removeAt(from)
        rules.add(to, item)

        notifyItemMoved(from, to)
    }

    fun removeAt(position: Int): String? {
        if (position !in rules.indices)
            return null

        val removed = rules.removeAt(position)

        notifyItemRemoved(position)

        return removed
    }

    fun insert(position: Int, rule: String) {
        val index = position.coerceIn(0, rules.size)

        rules.add(index, rule)

        notifyItemInserted(index)
    }

    fun replaceAll(list: List<String>) {
        rules.clear()
        rules.addAll(list)

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterCustomRuleBinding
                .inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]

        holder.binding.ruleTextView.text = rule
        val hits = hitCounts[rule] ?: 0L
        val summary = describe(rule).toString()

        holder.binding.ruleSubTextView.text = if (hits > 0)
            summary + holder.itemView.context.getString(R.string.rule_hit_suffix, hits)
        else
            summary
        holder.binding.ruleDeleteView.setOnClickListener { onDelete(rule) }
        holder.binding.ruleDragView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }

            false
        }
    }

    override fun getItemCount(): Int {
        return rules.size
    }
}