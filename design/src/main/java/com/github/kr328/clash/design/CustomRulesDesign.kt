package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.annotation.StringRes
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.adapter.CustomRuleAdapter
import com.github.kr328.clash.design.databinding.DesignCustomRulesBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.store.RuleTemplateStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Simplified editor for extra routing rules.
 *
 * Beginners only have to answer two questions: "which domain / IP" and "where
 * should it go". The resulting mihomo rule is generated for them and stored in
 * the persistent override, where the native side prepends it to the
 * subscription rules (kept separately for every subscription).
 *
 * Rules can be reordered by dragging (the order is the priority order), removed
 * by swiping with undo, saved as reusable templates, tested against a domain or
 * IP, imported/exported and shared.
 */
class CustomRulesDesign(
    context: Context,
    initialRules: List<String>,
    private val groupNames: List<String>,
) : Design<CustomRulesDesign.Request>(context) {
    sealed class Request {
        data class Persist(val rules: List<String>) : Request()
        object ImportRules : Request()
        object ExportRules : Request()
        object ShareRules : Request()
    }

    private val binding = DesignCustomRulesBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val templateStore = RuleTemplateStore(context)

    private var hitCounts: Map<String, Long> = emptyMap()

    private val adapter = CustomRuleAdapter(
        { describe(it) },
        { deleteRule(it) },
        { startDrag(it) },
    )

    private val dragHelper = ItemTouchHelper(DragCallback())

    private val swipePaint = Paint().apply {
        color = 0x28F44336
    }

    val rulesSnapshot: List<String>
        get() = adapter.rules.toList()

    private data class Preset(
        @StringRes val title: Int,
        val needsTarget: Boolean,
        val rules: List<String>,
    )

    private sealed class TemplateChoice {
        data class BuiltIn(val preset: Preset) : TemplateChoice()
        data class Custom(val name: String) : TemplateChoice()
        object SaveCurrent : TemplateChoice()
    }

    private val presets = listOf(
        Preset(
            R.string.preset_foreign_proxy,
            true,
            listOf(
                "DOMAIN-SUFFIX,google.com,%T%",
                "DOMAIN-SUFFIX,youtube.com,%T%",
                "DOMAIN-SUFFIX,netflix.com,%T%",
                "DOMAIN-SUFFIX,disneyplus.com,%T%",
                "DOMAIN-SUFFIX,twitter.com,%T%",
                "DOMAIN-SUFFIX,x.com,%T%",
                "DOMAIN-SUFFIX,instagram.com,%T%",
                "DOMAIN-SUFFIX,facebook.com,%T%",
                "DOMAIN-SUFFIX,telegram.org,%T%",
                "DOMAIN-SUFFIX,openai.com,%T%",
                "DOMAIN-SUFFIX,chatgpt.com,%T%",
                "DOMAIN-SUFFIX,github.com,%T%",
            ),
        ),
        Preset(
            R.string.preset_china_direct,
            false,
            listOf(
                "GEOIP,CN,DIRECT",
                "DOMAIN-SUFFIX,cn,DIRECT",
            ),
        ),
        Preset(
            R.string.preset_lan_direct,
            false,
            listOf(
                "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
                "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
                "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
                "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
            ),
        ),
    )

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
        binding.rulesList.applyLinearAdapter(context, adapter)
        binding.rulesList.bindAppBarElevation(binding.activityBarLayout)

        dragHelper.attachToRecyclerView(binding.rulesList)

        binding.addRuleView.setOnClickListener {
            launch { addRule() }
        }
        binding.presetView.setOnClickListener {
            launch { chooseTemplate() }
        }
        binding.importView.setOnClickListener {
            requests.trySend(Request.ImportRules)
        }
        binding.exportView.setOnClickListener {
            requests.trySend(Request.ExportRules)
        }
        binding.testView.setOnClickListener {
            launch { testRule() }
        }
        binding.shareView.setOnClickListener {
            requests.trySend(Request.ShareRules)
        }

        adapter.submit(initialRules)

        updateEmptyState()
    }

    suspend fun onRulesImported(lines: List<String>) {
        val imported = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(",") }

        if (imported.isEmpty()) {
            showToast(context.getString(R.string.rules_import_empty), ToastDuration.Long)

            return
        }

        var added = 0

        withContext(Dispatchers.Main) {
            imported.forEach { rule ->
                if (adapter.rules.none { it.equals(rule, ignoreCase = true) }) {
                    adapter.insert(adapter.itemCount, rule)

                    added++
                }
            }

            updateEmptyState()
        }

        if (added > 0) {
            persist()
        }

        showToast(context.getString(R.string.rules_import_done, added), ToastDuration.Short)
    }

    private fun updateEmptyState() {
        binding.emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        binding.dragHintView.visibility = if (adapter.itemCount > 1) View.VISIBLE else View.GONE

        updateSummary()
    }

    suspend fun setHitCounts(counts: Map<String, Long>) {
        withContext(Dispatchers.Main) {
            hitCounts = counts

            adapter.setHitCounts(counts)

            updateSummary()
        }
    }

    private fun updateSummary() {
        val rules = adapter.rules

        if (rules.isEmpty()) {
            binding.ruleSummaryView.text = ""

            return
        }

        var proxy = 0
        var direct = 0
        var reject = 0

        rules.forEach { rule ->
            when (rule.split(',').getOrNull(2)?.trim()?.uppercase()) {
                "DIRECT" -> direct++
                "REJECT" -> reject++
                else -> proxy++
            }
        }

        val hits = hitCounts.values.sum()

        binding.ruleSummaryView.text =
            context.getString(R.string.rule_summary, rules.size, proxy, direct, reject, hits)
    }

    private fun persist() {
        requests.trySend(Request.Persist(adapter.rules.toList()))
    }

    private fun startDrag(holder: RecyclerView.ViewHolder) {
        dragHelper.startDrag(holder)
    }

    private fun deleteRule(rule: String) {
        val position = adapter.rules.indexOf(rule)

        if (position < 0)
            return

        val removed = adapter.removeAt(position) ?: return

        updateEmptyState()
        persist()
        showUndo(removed, position)
    }

    private fun showUndo(rule: String, position: Int) {
        launch {
            showToast(context.getString(R.string.rule_deleted), ToastDuration.Long) {
                setAction(R.string.undo) {
                    adapter.insert(position, rule)

                    updateEmptyState()
                    persist()
                }
            }
        }
    }

    private suspend fun addRule() {
        val input = context.requestModelTextInput(
            initial = "",
            title = context.getString(R.string.rule_content_title),
            hint = context.getString(R.string.rule_content_hint),
        ).trim()

        if (input.isEmpty())
            return

        val target = chooseTarget() ?: return
        val rule = buildRule(input, target)

        if (adapter.rules.any { it.equals(rule, ignoreCase = true) })
            return

        adapter.insert(adapter.itemCount, rule)

        updateEmptyState()
        persist()
    }

    private suspend fun chooseTemplate() {
        val choices = mutableListOf<Pair<String, TemplateChoice>>()

        presets.forEach { choices += context.getString(it.title) to TemplateChoice.BuiltIn(it) }
        templateStore.names().forEach { choices += it to TemplateChoice.Custom(it) }
        choices += context.getString(R.string.template_save) to TemplateChoice.SaveCurrent

        val index = context.selectItem(
            context.getString(R.string.preset_title),
            choices.map { it.first },
        ) ?: return

        when (val choice = choices[index].second) {
            is TemplateChoice.BuiltIn -> applyPreset(choice.preset)
            is TemplateChoice.Custom -> applyCustomTemplate(choice.name)
            TemplateChoice.SaveCurrent -> saveCurrentAsTemplate()
        }
    }

    private suspend fun applyCustomTemplate(name: String) {
        val action = context.selectItem(
            name,
            listOf(
                context.getString(R.string.template_apply),
                context.getString(R.string.template_delete),
            ),
        ) ?: return

        if (action == 1) {
            templateStore.delete(name)

            showToast(context.getString(R.string.template_deleted), ToastDuration.Short)

            return
        }

        addRules(templateStore.load(name))
    }

    private suspend fun saveCurrentAsTemplate() {
        if (adapter.rules.isEmpty()) {
            showToast(context.getString(R.string.template_empty), ToastDuration.Long)

            return
        }

        val name = context.requestModelTextInput(
            initial = "",
            title = context.getString(R.string.template_name_title),
            hint = context.getString(R.string.template_name_hint),
        ).trim()

        if (name.isEmpty())
            return

        templateStore.save(name, adapter.rules.toList())

        showToast(context.getString(R.string.template_saved), ToastDuration.Short)
    }

    private suspend fun applyPreset(preset: Preset) {
        val target = if (preset.needsTarget) chooseTarget() ?: return else "DIRECT"

        addRules(preset.rules.map { it.replace("%T%", target) })
    }

    private suspend fun addRules(rules: List<String>) {
        var added = 0

        rules.forEach { rule ->
            if (adapter.rules.none { it.equals(rule, ignoreCase = true) }) {
                adapter.insert(adapter.itemCount, rule)

                added++
            }
        }

        updateEmptyState()

        if (added > 0) {
            persist()
        }

        showToast(context.getString(R.string.preset_added, added), ToastDuration.Short)
    }

    private suspend fun testRule() {
        if (adapter.rules.isEmpty()) {
            showToast(context.getString(R.string.custom_rules_empty), ToastDuration.Long)

            return
        }

        val input = context.requestModelTextInput(
            initial = "",
            title = context.getString(R.string.rule_test_title),
            hint = context.getString(R.string.rule_test_hint),
        ).trim()

        if (input.isEmpty())
            return

        val matched = adapter.rules.firstOrNull { matches(it, input) }

        val message = if (matched == null)
            context.getString(R.string.rule_test_missed)
        else
            context.getString(R.string.rule_test_matched, matched)

        val needsCore = adapter.rules.any { rule ->
            rule.substringBefore(',').trim().uppercase() in CORE_ONLY_TYPES
        }

        val text = if (needsCore)
            message + "\n\n" + context.getString(R.string.rule_test_geo_hint)
        else
            message

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rule_test_result_title)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun matches(rule: String, input: String): Boolean {
        val parts = rule.split(',')

        val type = parts.getOrNull(0)?.trim()?.uppercase() ?: return false
        val argument = parts.getOrNull(1)?.trim()?.lowercase() ?: return false
        val value = input.trim().lowercase()

        if (value.isEmpty() || argument.isEmpty())
            return false

        return when (type) {
            "DOMAIN-SUFFIX" -> value == argument || value.endsWith(".$argument")
            "DOMAIN" -> value == argument
            "DOMAIN-KEYWORD" -> value.contains(argument)
            "IP-CIDR", "IP-CIDR6" -> ipInCidr(value, argument)
            else -> false
        }
    }

    private fun ipInCidr(ip: String, cidr: String): Boolean {
        val address = parseAddress(ip) ?: return false

        val parts = cidr.split('/')
        val network = parseAddress(parts.getOrNull(0) ?: return false) ?: return false

        if (address.size != network.size)
            return false

        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: (network.size * 8)

        if (prefix !in 0..(network.size * 8))
            return false

        var bits = prefix

        for (i in address.indices) {
            if (bits <= 0)
                break

            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF

            if ((address[i].toInt() and mask) != (network[i].toInt() and mask))
                return false

            bits -= 8
        }

        return true
    }

    /**
     * Parses an IPv4 / IPv6 literal without ever resolving a host name. Only
     * strings that really look like an address reach [InetAddress], so testing
     * a domain can never trigger a DNS lookup here.
     */
    private fun parseAddress(value: String): ByteArray? {
        val text = value.trim()

        if (text.isEmpty())
            return null

        if (!text.contains(':') && !text.matches(IPV4_LITERAL))
            return null

        return try {
            InetAddress.getByName(text).address
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun chooseTarget(): String? {
        val entries = mutableListOf<Pair<CharSequence, String>>()
        val primary = groupNames.firstOrNull()

        entries += context.getString(R.string.rule_target_proxy) to (primary ?: "PROXY")

        groupNames.drop(1).forEach { name ->
            entries += context.getString(R.string.rule_target_group, name) to name
        }

        entries += context.getString(R.string.rule_target_direct) to "DIRECT"
        entries += context.getString(R.string.rule_target_reject) to "REJECT"

        val index = context.selectItem(
            context.getString(R.string.rule_target_title),
            entries.map { it.first },
        ) ?: return null

        return entries[index].second
    }

    private fun buildRule(input: String, target: String): String {
        val value = input.trim()

        return when {
            value.contains("/") -> "IP-CIDR,$value,$target,no-resolve"
            value.contains(".") -> "DOMAIN-SUFFIX,$value,$target"
            else -> "DOMAIN-KEYWORD,$value,$target"
        }
    }

    private fun describe(rule: String): String {
        val parts = rule.split(',')

        val type = parts.getOrNull(0)?.trim().orEmpty()
        val target = parts.getOrNull(2)?.trim().orEmpty()

        val typeLabel = labelOf(type)?.let { context.getString(it) } ?: type

        val targetLabel = when (target) {
            "DIRECT" -> context.getString(R.string.rule_target_direct)
            "REJECT" -> context.getString(R.string.rule_target_reject)
            "PROXY" -> context.getString(R.string.rule_target_proxy)
            else -> target
        }

        return context.getString(R.string.custom_rule_summary, typeLabel, targetLabel)
    }

    private fun labelOf(type: String): Int? = when (type) {
        "DOMAIN-SUFFIX" -> R.string.rule_type_domain_suffix
        "DOMAIN" -> R.string.rule_type_domain
        "DOMAIN-KEYWORD" -> R.string.rule_type_domain_keyword
        "IP-CIDR", "IP-CIDR6" -> R.string.rule_type_ip_cidr
        "PROCESS-NAME" -> R.string.rule_type_process
        "GEOIP" -> R.string.rule_type_geoip
        "GEOSITE" -> R.string.rule_type_geosite
        "RULE-SET" -> R.string.rule_type_rule_set
        else -> null
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.START or ItemTouchHelper.END,
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            val removed = adapter.removeAt(position) ?: return

            updateEmptyState()
            persist()
            showUndo(removed, position)
        }

        override fun isLongPressDragEnabled(): Boolean = true

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val view = viewHolder.itemView

                if (dX < 0) {
                    canvas.drawRect(
                        view.right + dX,
                        view.top.toFloat(),
                        view.right.toFloat(),
                        view.bottom.toFloat(),
                        swipePaint,
                    )
                } else {
                    canvas.drawRect(
                        view.left.toFloat(),
                        view.top.toFloat(),
                        view.left + dX,
                        view.bottom.toFloat(),
                        swipePaint,
                    )
                }
            }

            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)

            persist()
        }
    }

    private suspend fun Context.selectItem(
        title: CharSequence,
        items: List<CharSequence>,
    ): Int? {
        return suspendCancellableCoroutine { continuation ->
            var selected = false

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items.toTypedArray()) { _, which ->
                    selected = true

                    if (continuation.isActive)
                        continuation.resume(which)
                }
                .setOnDismissListener {
                    if (!selected && continuation.isActive)
                        continuation.resume(null)
                }
                .create()

            continuation.invokeOnCancellation { dialog.dismiss() }

            dialog.show()
        }
    }

    private companion object {
        val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        val CORE_ONLY_TYPES = setOf(
            "GEOIP",
            "GEOSITE",
            "RULE-SET",
            "PROCESS-NAME",
            "SRC-PORT",
            "DST-PORT",
            "PROCESS-PATH",
            "SCRIPT",
        )
    }
}