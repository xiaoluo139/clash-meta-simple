package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.databinding.DesignSimpleBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Beginner friendly home screen.
 *
 * The whole page answers three questions at a glance:
 *  - is the proxy running?
 *  - how do I start it?  -> two explicit buttons: rules mode / global mode
 *  - what is it doing now? -> current mode + forwarded traffic
 *
 * The two start buttons write the routing mode into the persistent override
 * first, so the choice survives a restart. While the tunnel is up the mode can
 * still be switched with the segmented control.
 */
class SimpleDesign(context: Context) : Design<SimpleDesign.Request>(context) {
    sealed class Request {
        data class StartWithMode(val mode: TunnelState.Mode) : Request()
        object Stop : Request()
        data class PatchMode(val mode: TunnelState.Mode) : Request()
        object OpenNodes : Request()
        object UpdateProfile : Request()
        object OpenProfiles : Request()
        object OpenAccessControl : Request()
        object OpenCustomRules : Request()
        object OpenSettings : Request()
        object OpenAdvanced : Request()
    }

    private val binding = DesignSimpleBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private var currentMode: TunnelState.Mode? = null
    private var nodeName: String? = null
    private var nodeDelay: Int? = null

    init {
        binding.self = this

        val started = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val stopped = context.resolveThemedColor(R.attr.colorClashStopped)
        val surface = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val onPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)

        binding.orbView.setStateColors(started, stopped)

        // The orb is a pure status indicator; the two cards below are the controls.
        binding.orbView.isClickable = false
        binding.orbView.isFocusable = false

        binding.startRuleCard.setCardBackgroundColor(started)
        binding.stopCard.setCardBackgroundColor(stopped)

        binding.modeControl.configure(surface, started, onPrimary, onSurface)
        binding.modeControl.setItems(
            listOf(
                context.getString(R.string.rule_mode),
                context.getString(R.string.global_mode),
                context.getString(R.string.direct_mode),
            )
        )
        binding.modeControl.setOnSelectedListener { index ->
            requests.trySend(Request.PatchMode(modeAt(index)))
        }

        binding.startRuleCard.setOnClickListener {
            requests.trySend(Request.StartWithMode(TunnelState.Mode.Rule))
        }
        binding.startGlobalCard.setOnClickListener {
            requests.trySend(Request.StartWithMode(TunnelState.Mode.Global))
        }
        binding.stopCard.setOnClickListener {
            requests.trySend(Request.Stop)
        }

        binding.advancedButton.setOnClickListener { requests.trySend(Request.OpenAdvanced) }
        binding.profileLabel.setOnClickListener { requests.trySend(Request.OpenProfiles) }
        binding.nodeLabel.setOnClickListener { requests.trySend(Request.OpenNodes) }
        binding.updateProfileLabel.setOnClickListener { requests.trySend(Request.UpdateProfile) }
        binding.accessControlLabel.setOnClickListener { requests.trySend(Request.OpenAccessControl) }
        binding.rulesLabel.setOnClickListener { requests.trySend(Request.OpenCustomRules) }
        binding.settingsLabel.setOnClickListener { requests.trySend(Request.OpenSettings) }

        binding.statusView.setText(R.string.not_connected)
        binding.profileLabel.subtext = context.getString(R.string.not_selected)
        binding.nodeLabel.subtext = context.getString(R.string.not_selected)
        binding.accessControlLabel.subtext = context.getString(R.string.simple_access_control_hint)
        binding.rulesLabel.subtext = context.getString(R.string.simple_rules_hint)
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.orbView.setConnecting(false)
            binding.orbView.setConnected(running)

            binding.statusView.setText(if (running) R.string.connected else R.string.not_connected)

            binding.groupStopped.visibility = if (running) View.GONE else View.VISIBLE
            binding.groupRunning.visibility = if (running) View.VISIBLE else View.GONE

            binding.modeStatusView.visibility = if (running) View.VISIBLE else View.GONE

            if (!running) {
                binding.trafficView.text = ""
            }

            updateModeStatus()
        }
    }

    suspend fun setConnecting() {
        withContext(Dispatchers.Main) {
            binding.orbView.setConnected(false)
            binding.orbView.setConnecting(true)
            binding.statusView.setText(R.string.connecting)
            binding.trafficView.text = ""
        }
    }

    suspend fun setForwarded(text: String) {
        withContext(Dispatchers.Main) {
            binding.trafficView.text = text
        }
    }

    suspend fun setMode(mode: TunnelState.Mode?) {
        withContext(Dispatchers.Main) {
            currentMode = mode

            binding.modeControl.setSelection(indexOf(mode))

            updateModeStatus()
        }
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileLabel.subtext = name ?: context.getString(R.string.not_selected)
        }
    }

    suspend fun setNodeName(name: String?) {
        withContext(Dispatchers.Main) {
            nodeName = name

            updateNodeSubtext()
        }
    }

    suspend fun setNodeDelay(delay: Int?) {
        withContext(Dispatchers.Main) {
            nodeDelay = delay

            updateNodeSubtext()
        }
    }

    suspend fun setUpdatingProfile(updating: Boolean) {
        withContext(Dispatchers.Main) {
            binding.updateProfileLabel.isEnabled = !updating

            binding.updateProfileLabel.subtext = if (updating)
                context.getString(R.string.update_profile_doing)
            else
                context.getString(R.string.update_profile_hint)
        }
    }

    suspend fun setLastStartMode(mode: TunnelState.Mode?) {
        withContext(Dispatchers.Main) {
            val label = modeLabel(mode)

            if (label == null) {
                binding.lastModeView.visibility = View.GONE
            } else {
                binding.lastModeView.visibility = View.VISIBLE
                binding.lastModeView.text = context.getString(R.string.last_start_mode, label)
            }
        }
    }

    suspend fun setCustomRuleCount(count: Int) {
        withContext(Dispatchers.Main) {
            binding.rulesLabel.subtext = if (count > 0)
                context.getString(R.string.simple_rules_count, count)
            else
                context.getString(R.string.simple_rules_hint)
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun updateNodeSubtext() {
        val name = nodeName

        if (name.isNullOrBlank()) {
            binding.nodeLabel.subtext = context.getString(R.string.not_selected)

            return
        }

        val delay = nodeDelay
        val state = when {
            delay == null -> null
            delay in 1..Short.MAX_VALUE -> context.getString(R.string.node_delay_ms, delay)
            delay == 0 -> null
            else -> context.getString(R.string.node_delay_timeout)
        }

        binding.nodeLabel.subtext = if (state == null)
            name
        else
            context.getString(R.string.node_with_state, name, state)
    }

    private fun updateModeStatus() {
        binding.modeStatusView.text = modeLabel(currentMode) ?: ""
    }

    private fun modeLabel(mode: TunnelState.Mode?): String? = when (mode) {
        TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
        TunnelState.Mode.Global -> context.getString(R.string.global_mode)
        TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
        else -> null
    }

    private fun modeAt(index: Int): TunnelState.Mode = when (index) {
        1 -> TunnelState.Mode.Global
        2 -> TunnelState.Mode.Direct
        else -> TunnelState.Mode.Rule
    }

    private fun indexOf(mode: TunnelState.Mode?): Int = when (mode) {
        TunnelState.Mode.Global -> 1
        TunnelState.Mode.Direct -> 2
        else -> 0
    }
}