package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.CustomRulesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R as DesignR
import java.util.concurrent.TimeUnit
class CustomRulesActivity : BaseActivity<CustomRulesDesign>() {
    override suspend fun main() {
        // Rules are stored per subscription, keyed by the active profile UUID.
        val activeProfile = try {
            withProfile { queryActive()?.uuid?.toString() }
        } catch (e: Exception) {
            null
        }

        val stored = loadStoredRules(activeProfile)

        val groups = try {
            withClash {
                queryProxyGroupNames(false)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val design = CustomRulesDesign(this, stored, groups)

        setContentDesign(design)

        design.setHitCounts(loadHitCounts(design.rulesSnapshot))

        val ticker = ticker(TimeUnit.SECONDS.toMillis(3))

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                if (activityStarted) {
                    ticker.onReceive {
                        design.setHitCounts(loadHitCounts(design.rulesSnapshot))
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is CustomRulesDesign.Request.Persist -> {
                            persistRules(activeProfile, it.rules)

                            design.setHitCounts(loadHitCounts(it.rules))
                        }
                        CustomRulesDesign.Request.ImportRules -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*"
                            )

                            if (uri != null) {
                                design.onRulesImported(readRules(uri))
                            }
                        }
                        CustomRulesDesign.Request.ShareRules -> {
                            val rules = design.rulesSnapshot

                            if (rules.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, rules.joinToString("\n"))

                                startActivity(
                                    Intent.createChooser(intent, getString(DesignR.string.rules_share))
                                )
                            }
                        }
                        CustomRulesDesign.Request.ExportRules -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.CreateDocument("text/plain"),
                                "clash-custom-rules.txt"
                            )

                            if (uri != null) {
                                writeRules(uri, design.rulesSnapshot)

                                design.showToast(
                                    getString(DesignR.string.rules_export_done),
                                    ToastDuration.Short
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadStoredRules(uuid: String?): List<String> {
        return try {
            val config = withClash { queryOverride(Clash.OverrideSlot.Persist) }
            val map = config.customRulesByProfile

            if (uuid != null && map != null && map.containsKey(uuid))
                map[uuid].orEmpty()
            else
                config.customRules.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun persistRules(uuid: String?, rules: List<String>) {
        withClash {
            val config = queryOverride(Clash.OverrideSlot.Persist)

            if (uuid == null) {
                config.customRules = rules.takeIf { it.isNotEmpty() }
            } else {
                val map = config.customRulesByProfile.orEmpty().toMutableMap()

                if (rules.isEmpty())
                    map.remove(uuid)
                else
                    map[uuid] = rules

                config.customRulesByProfile = map.ifEmpty { null }

                // Migrate the legacy shared list away on the first edit so that
                // every subscription really keeps its own rules.
                config.customRules = null
            }

            patchOverride(Clash.OverrideSlot.Persist, config)
        }
    }

    /**
     * The native side prepends the custom rules to the subscription rules, so
     * the first [rules].size entries of the stats belong to the user. The type
     * is verified as a cheap sanity check before trusting the index.
     */
    private suspend fun loadHitCounts(rules: List<String>): Map<String, Long> {
        return try {
            val stats = withClash { queryRuleStats() }

            rules.mapIndexedNotNull { index, rule ->
                val stat = stats.getOrNull(index) ?: return@mapIndexedNotNull null

                if (!matchesNativeType(rule, stat.type)) return@mapIndexedNotNull null

                stat.hitCount.takeIf { it > 0 }?.let { rule to it }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun matchesNativeType(rule: String, nativeType: String): Boolean {
        val type = rule.substringBefore(',').trim().uppercase()

        val expected = when (type) {
            "DOMAIN-SUFFIX" -> "DomainSuffix"
            "DOMAIN" -> "Domain"
            "DOMAIN-KEYWORD" -> "DomainKeyword"
            "DOMAIN-REGEX" -> "DomainRegex"
            "IP-CIDR", "IP-CIDR6" -> "IPCIDR"
            "GEOIP" -> "GeoIP"
            "GEOSITE" -> "GeoSite"
            "RULE-SET" -> "RuleSet"
            "PROCESS-NAME" -> "ProcessName"
            "MATCH" -> "MATCH"
            else -> return true
        }

        return nativeType == expected
    }

    private suspend fun readRules(uri: Uri): List<String> {
        return try {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readLines()
                } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun writeRules(uri: Uri, rules: List<String>) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(rules.joinToString("\n").toByteArray())
            }
        }
    }
}