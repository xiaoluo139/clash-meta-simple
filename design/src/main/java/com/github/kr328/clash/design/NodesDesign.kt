package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.adapter.NodeAdapter
import com.github.kr328.clash.design.databinding.DesignNodesBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Beginner friendly node list.
 *
 * Shows every entry of the selected proxy group together with its measured
 * latency, lets the user start a speed test, automatically pick the fastest
 * node, refresh the node list and of course select a node by hand.
 */
class NodesDesign(
    context: Context,
    initialGroups: List<String>,
) : Design<NodesDesign.Request>(context) {
    sealed class Request {
        object UrlTest : Request()
        object AutoSelect : Request()
        object Refresh : Request()
        data class SwitchGroup(val name: String) : Request()
        data class Select(val name: String) : Request()
        data class ChangeSort(val sort: ProxySort) : Request()
    }

    private val binding = DesignNodesBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val adapter = NodeAdapter(
        context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary),
        { requests.trySend(Request.Select(it)) },
    )

    private var groups: List<String> = initialGroups
    private var sort: ProxySort = ProxySort.Default

    var currentGroup: String = initialGroups.firstOrNull().orEmpty()
        private set

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
        binding.nodeList.applyLinearAdapter(context, adapter)

        binding.testView.setOnClickListener { requests.trySend(Request.UrlTest) }
        binding.autoSelectView.setOnClickListener { requests.trySend(Request.AutoSelect) }
        binding.refreshView.setOnClickListener { requests.trySend(Request.Refresh) }
        binding.groupView.setOnClickListener {
            launch { chooseGroup() }
        }
        binding.sortView.setOnClickListener {
            launch { chooseSort() }
        }

        updateGroupLabel()
        updateSortLabel()
    }

    suspend fun setGroups(names: List<String>) {
        withContext(Dispatchers.Main) {
            groups = names

            if (currentGroup !in names) {
                currentGroup = names.firstOrNull().orEmpty()
            }

            updateGroupLabel()
        }
    }

    suspend fun setNodes(nodes: List<Proxy>, selected: String) {
        withContext(Dispatchers.Main) {
            adapter.submit(nodes, selected)

            binding.emptyView.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    suspend fun setTesting(testing: Boolean) {
        withContext(Dispatchers.Main) {
            binding.testView.isEnabled = !testing
            binding.autoSelectView.isEnabled = !testing
            binding.refreshView.isEnabled = !testing

            binding.testView.text = if (testing)
                context.getString(R.string.node_testing)
            else
                context.getString(R.string.node_test)
        }
    }

    suspend fun setMessage(message: CharSequence?) {
        withContext(Dispatchers.Main) {
            binding.nodeStatusView.text = message ?: ""
        }
    }

    suspend fun setSort(value: ProxySort) {
        withContext(Dispatchers.Main) {
            sort = value

            updateSortLabel()
        }
    }

    private fun updateSortLabel() {
        binding.sortView.text = context.getString(R.string.node_sort_label, sortLabel(sort))
    }

    private fun sortLabel(value: ProxySort): String = when (value) {
        ProxySort.Title -> context.getString(R.string.name)
        ProxySort.Delay -> context.getString(R.string.delay)
        else -> context.getString(R.string.node_sort_default)
    }

    private suspend fun chooseSort() {
        val options = listOf(ProxySort.Default, ProxySort.Title, ProxySort.Delay)

        val index = context.selectItem(
            context.getString(R.string.node_sort_title),
            options.map { sortLabel(it) },
        ) ?: return

        val value = options[index]

        if (value == sort)
            return

        sort = value

        updateSortLabel()

        requests.trySend(Request.ChangeSort(value))
    }

    private fun updateGroupLabel() {
        binding.groupView.visibility = if (groups.size > 1) View.VISIBLE else View.GONE
        binding.groupView.text = if (currentGroup.isEmpty())
            ""
        else
            context.getString(R.string.node_group_label, currentGroup)
    }

    private suspend fun chooseGroup() {
        if (groups.size <= 1)
            return

        val index = context.selectItem(
            context.getString(R.string.node_group_title),
            groups,
        ) ?: return

        val name = groups[index]

        if (name == currentGroup)
            return

        currentGroup = name

        updateGroupLabel()

        requests.trySend(Request.SwitchGroup(name))
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
}