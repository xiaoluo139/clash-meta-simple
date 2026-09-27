package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.SimpleDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<Design<*>>() {
    override suspend fun main() {
        if (uiStore.simpleMode)
            mainSimple()
        else
            mainAdvanced()
    }

    // ---------------------------------------------------------------------
    // Simple mode
    // ---------------------------------------------------------------------

    private suspend fun mainSimple() {
        val design = SimpleDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        var nodeRefreshCounter = 0

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is SimpleDesign.Request.StartWithMode -> {
                            withClash {
                                val overrideConfig = queryOverride(Clash.OverrideSlot.Persist)

                                overrideConfig.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Persist, overrideConfig)
                            }

                            uiStore.lastStartMode = modeCode(it.mode)

                            design.setMode(it.mode)
                            design.setConnecting()
                            startClash(design)
                        }
                        SimpleDesign.Request.Stop -> stopClashService()
                        SimpleDesign.Request.OpenNodes ->
                            startActivity(NodesActivity::class.intent)
                        SimpleDesign.Request.UpdateProfile -> {
                            val active = withProfile { queryActive() }

                            if (active == null || !active.imported || active.type == Profile.Type.File) {
                                design.showToast(
                                    getString(DesignR.string.update_profile_unavailable),
                                    ToastDuration.Long
                                )
                            } else {
                                design.setUpdatingProfile(true)

                                try {
                                    withProfile { update(active.uuid) }

                                    design.showToast(
                                        getString(DesignR.string.update_profile_done),
                                        ToastDuration.Long
                                    )
                                } catch (e: Exception) {
                                    design.showToast(
                                        getString(
                                            DesignR.string.update_profile_failed,
                                            e.message ?: ""
                                        ),
                                        ToastDuration.Long
                                    )
                                } finally {
                                    design.setUpdatingProfile(false)
                                }
                            }
                        }
                        SimpleDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        SimpleDesign.Request.OpenAccessControl ->
                            startActivity(AccessControlActivity::class.intent)
                        SimpleDesign.Request.OpenCustomRules ->
                            startActivity(CustomRulesActivity::class.intent)
                        SimpleDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        SimpleDesign.Request.OpenAdvanced -> {
                            uiStore.simpleMode = false
                            recreate()
                        }
                        is SimpleDesign.Request.PatchMode -> {
                            withClash {
                                val overrideConfig = queryOverride(Clash.OverrideSlot.Persist)

                                overrideConfig.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Persist, overrideConfig)
                            }

                            design.setMode(it.mode)
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()

                        nodeRefreshCounter++

                        if (nodeRefreshCounter >= NODE_REFRESH_INTERVAL_SECONDS) {
                            nodeRefreshCounter = 0

                            design.fetchNode()
                        }
                    }
                }
            }
        }
    }

    private suspend fun SimpleDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }

        setMode(state.mode)

        withProfile {
            setProfileName(queryActive()?.name)
        }

        fetchNode()

        val customRuleCount = try {
            withClash {
                queryOverride(Clash.OverrideSlot.Persist).customRules?.size ?: 0
            }
        } catch (e: Exception) {
            0
        }

        setCustomRuleCount(customRuleCount)

        setLastStartMode(modeFromCode(uiStore.lastStartMode))
    }

    private companion object {
        const val NODE_REFRESH_INTERVAL_SECONDS = 5
    }

    private fun modeCode(mode: TunnelState.Mode): Int = when (mode) {
        TunnelState.Mode.Rule -> 0
        TunnelState.Mode.Global -> 1
        TunnelState.Mode.Direct -> 2
        else -> -1
    }

    private fun modeFromCode(code: Int): TunnelState.Mode? = when (code) {
        0 -> TunnelState.Mode.Rule
        1 -> TunnelState.Mode.Global
        2 -> TunnelState.Mode.Direct
        else -> null
    }

    private suspend fun SimpleDesign.fetchTraffic() {
        val forwarded = withClash {
            queryTrafficTotal()
        }

        setForwarded(getString(DesignR.string.format_traffic_forwarded, forwarded.trafficTotal()))
    }

    private suspend fun SimpleDesign.fetchNode() {
        val currentNode = queryCurrentNode()

        setNodeName(currentNode?.first)
        setNodeDelay(currentNode?.second)
    }

    /**
     * @return the currently selected node of the primary group and its last
     *         measured latency (0 when it was never tested).
     */
    private suspend fun queryCurrentNode(): Pair<String, Int>? {
        return try {
            val names = withClash {
                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
            }
            val first = names.firstOrNull() ?: return null

            val group = withClash {
                queryProxyGroup(first, uiStore.nodeSort)
            }

            val now = group.now.takeIf { it.isNotBlank() } ?: return null
            val delay = group.proxies.firstOrNull { it.name == now }?.delay ?: 0

            now to delay
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Advanced (classic) mode
    // ---------------------------------------------------------------------

    private suspend fun mainAdvanced() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                startClash(design)
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        MainDesign.Request.OpenSimpleMode -> {
                            uiStore.simpleMode = true
                            recreate()
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private suspend fun startClash(design: Design<*>) {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            design.showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            if (design is SimpleDesign)
                design.setClashRunning(clashRunning)

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}