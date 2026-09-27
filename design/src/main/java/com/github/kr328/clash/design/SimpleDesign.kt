package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Color
import android.view.View
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.databinding.DesignSimpleBinding
import com.github.kr328.clash.design.util.blendColor
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Beginner friendly home screen.
 *
 * Interaction model:
 *  - the big orb at the top is the **only** master switch: tap to start / stop
 *  - the two cards below only **select the routing mode** (rule / global) and
 *    never start the tunnel by themselves
 *
 * The selected mode is written into the persistent override, so it survives a
 * restart; while the tunnel is running the same cards switch the mode live.
 */
class SimpleDesign(context: Context) : Design<SimpleDesign.Request>(context) {
    sealed class Request {
        object ToggleStatus : Request()
        data class SelectMode(val mode: TunnelState.Mode) : Request()
        object UpdateProfile : Request()
        object AutoSelectFastest : Request()
        object OpenNodes : Request()
        object OpenProfiles : Request()
        object OpenAccessControl : Request()
        object OpenCustomRules : Request()
        object OpenIpCheck : Request()
        object OpenSettings : Request()
        object OpenAdvanced : Request()
    }

    private val binding = DesignSimpleBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val primaryColor = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    private val surfaceColor = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    private val selectedCardColor = blendColor(surfaceColor, primaryColor, 0.16f)

    private var currentMode: TunnelState.Mode? = null
    private var nodeName: String? = null
    private var nodeDelay: Int? = null

    init {
        binding.self = this

        val stoppedColor = context.resolveThemedColor(R.attr.colorClashStopped)

        binding.orbView.setStateColors(primaryColor, stoppedColor)

        // The orb is the master switch.
        binding.orbView.isClickable = true
        binding.orbView.isFocusable = true
        binding.orbView.setOnClickListener {
            requests.trySend(Request.ToggleStatus)
        }

        // The two cards only select the mode.
        binding.ruleModeCard.setSelectedColor(primaryColor)
        binding.globalModeCard.setSelectedColor(primaryColor)
        binding.ruleModeCard.setOnClickListener {
            requests.trySend(Request.SelectMode(TunnelState.Mode.Rule))
        }
        binding.globalModeCard.setOnClickListener {
            requests.trySend(Request.SelectMode(TunnelState.Mode.Global))
        }

        binding.advancedButton.setOnClickListener { requests.trySend(Request.OpenAdvanced) }
        binding.profileLabel.setOnClickListener { requests.trySend(Request.OpenProfiles) }
        binding.nodeLabel.setOnClickListener { requests.trySend(Request.OpenNodes) }
        binding.autoSelectLabel.setOnClickListener { requests.trySend(Request.AutoSelectFastest) }
        binding.updateProfileLabel.setOnClickListener { requests.trySend(Request.UpdateProfile) }
        binding.ipCheckLabel.setOnClickListener { requests.trySend(Request.OpenIpCheck) }
        binding.accessControlLabel.setOnClickListener { requests.trySend(Request.OpenAccessControl) }
        binding.rulesLabel.setOnClickListener { requests.trySend(Request.OpenCustomRules) }
        binding.settingsLabel.setOnClickListener { requests.trySend(Request.OpenSettings) }

        binding.statusView.setText(R.string.not_connected)
        binding.profileLabel.subtext = context.getString(R.string.not_selected)
        binding.nodeLabel.subtext = context.getString(R.string.not_selected)
        binding.accessControlLabel.subtext = context.getString(R.string.simple_access_control_hint)
        binding.rulesLabel.subtext = context.getString(R.string.simple_rules_hint)

        updateModeStatus()
        updateNodeSubtext()
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.orbView.setConnecting(false)
            binding.orbView.setConnected(running)

            binding.statusView.setText(if (running) R.string.connected else R.string.not_connected)

            binding.tapHintView.setText(
                if (running) R.string.tap_to_disconnect else R.string.tap_to_connect
            )

            if (!running) {
                binding.trafficView.text = ""
            }
        }
    }

    suspend fun setConnecting() {
        withContext(Dispatchers.Main) {
            binding.orbView.setConnected(false)
            binding.orbView.setConnecting(true)

            binding.statusView.setText(R.string.connecting)
            binding.tapHintView.setText(R.string.tap_to_connect)
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

            updateModeStatus()
            updateModeSelection()
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

    suspend fun setCustomRuleCount(count: Int) {
        withContext(Dispatchers.Main) {
            binding.rulesLabel.subtext = if (count > 0)
                context.getString(R.string.simple_rules_count, count)
            else
                context.getString(R.string.simple_rules_hint)
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

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun updateModeStatus() {
        val label = modeLabel(currentMode)

        binding.modeStatusView.text = if (label == null)
            ""
        else
            context.getString(R.string.current_mode_is, label)
    }

    private fun updateModeSelection() {
        applyModeCard(binding.ruleModeCard, currentMode == TunnelState.Mode.Rule)
        applyModeCard(binding.globalModeCard, currentMode == TunnelState.Mode.Global)
    }

    private fun applyModeCard(card: com.github.kr328.clash.design.view.LargeActionCard, selected: Boolean) {
        card.setSelectionMark(selected)
        card.setCardBackgroundColor(if (selected) selectedCardColor else surfaceColor)
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

    private fun modeLabel(mode: TunnelState.Mode?): String? = when (mode) {
        TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
        TunnelState.Mode.Global -> context.getString(R.string.global_mode)
        TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
        else -> null
    }

}