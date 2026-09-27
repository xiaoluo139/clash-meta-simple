package com.github.kr328.clash

import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.NodesDesign
import com.github.kr328.clash.util.AutoSelectResult
import com.github.kr328.clash.util.autoSelectFastestNode
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import com.github.kr328.clash.design.R as DesignR

class NodesActivity : BaseActivity<NodesDesign>() {
    override suspend fun main() {
        val groups = queryGroups()

        val design = NodesDesign(this, groups)

        setContentDesign(design)

        design.setGroups(groups)
        design.setSort(uiStore.nodeSort)

        reload(design, design.currentGroup)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded, Event.ProfileChanged -> {
                            val newGroups = queryGroups()

                            design.setGroups(newGroups)

                            reload(design, design.currentGroup)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NodesDesign.Request.UrlTest -> {
                            val group = design.currentGroup

                            if (group.isEmpty()) {
                                design.setMessage(getString(DesignR.string.node_empty))
                            } else {
                                design.setTesting(true)
                                design.setMessage(getString(DesignR.string.node_testing))

                                try {
                                    withClash { healthCheck(group) }
                                    design.setMessage(getString(DesignR.string.node_test_done))
                                } catch (e: Exception) {
                                    design.setMessage(e.message)
                                } finally {
                                    design.setTesting(false)
                                }

                                reload(design, group)
                            }
                        }
                        NodesDesign.Request.AutoSelect -> {
                            design.setTesting(true)
                            design.setMessage(getString(DesignR.string.node_auto_selecting))

                            try {
                                autoSelect(design)
                            } catch (e: Exception) {
                                design.setMessage(e.message)
                            } finally {
                                design.setTesting(false)
                            }
                        }
                        NodesDesign.Request.Refresh -> {
                            design.setTesting(true)

                            try {
                                refreshProviders()
                            } catch (e: Exception) {
                                // a failed provider update should not break the refresh
                            }

                            val newGroups = queryGroups()

                            design.setGroups(newGroups)

                            reload(design, design.currentGroup)

                            design.setTesting(false)
                            design.setMessage(getString(DesignR.string.node_refreshed))
                        }
                        is NodesDesign.Request.SwitchGroup -> {
                            design.setMessage(null)

                            reload(design, it.name)
                        }
                        is NodesDesign.Request.ChangeSort -> {
                            uiStore.nodeSort = it.sort

                            design.setSort(it.sort)

                            reload(design, design.currentGroup)
                        }
                        is NodesDesign.Request.Select -> {
                            val group = design.currentGroup

                            if (group.isNotEmpty()) {
                                val patched = withClash { patchSelector(group, it.name) }
                                val proxyGroup = withClash { queryProxyGroup(group, uiStore.nodeSort) }

                                design.setNodes(proxyGroup.proxies, proxyGroup.now)

                                design.setMessage(
                                    if (patched)
                                        getString(DesignR.string.node_selected, it.name)
                                    else
                                        getString(DesignR.string.node_auto_managed)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun queryGroups(): List<String> {
        return try {
            withClash {
                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun reload(design: NodesDesign, group: String) {
        if (group.isEmpty()) {
            design.setNodes(emptyList(), "")

            return
        }

        try {
            val proxyGroup = withClash {
                queryProxyGroup(group, uiStore.nodeSort)
            }

            design.setNodes(proxyGroup.proxies, proxyGroup.now)
        } catch (e: Exception) {
            design.setNodes(emptyList(), "")
            design.setMessage(e.message)
        }
    }

    /**
     * 自动选择最快节点。
     *
     * 只考虑「测速通过且延迟有效」的节点（排除节点组、未测速、超时），
     * 优先 3 秒以内的；若一次测速没有任何可用节点会自动重测一次。
     */
    private suspend fun autoSelect(design: NodesDesign) {
        val group = design.currentGroup

        if (group.isEmpty()) {
            design.setMessage(getString(DesignR.string.node_auto_failed))

            return
        }

        when (val result = autoSelectFastestNode(uiStore.proxyExcludeNotSelectable, group)) {
            is AutoSelectResult.Selected -> {
                reload(design, group)

                design.setMessage(
                    getString(DesignR.string.node_auto_selected_delay, result.node, result.delay)
                )
            }
            is AutoSelectResult.Managed -> {
                reload(design, group)

                design.setMessage(getString(DesignR.string.node_auto_managed))
            }
            is AutoSelectResult.NoNode -> {
                reload(design, group)

                design.setMessage(getString(DesignR.string.node_auto_failed))
            }
            AutoSelectResult.NoGroup ->
                design.setMessage(getString(DesignR.string.node_auto_failed))
        }
    }

    private suspend fun refreshProviders() {
        val providers = withClash {
            queryProviders().filter { it.type == Provider.Type.Proxy }
        }

        providers.forEach { provider ->
            try {
                withClash { updateProvider(provider.type, provider.name) }
            } catch (e: Exception) {
                // ignore single provider failure
            }
        }
    }
}