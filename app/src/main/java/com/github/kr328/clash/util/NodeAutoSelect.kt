package com.github.kr328.clash.util

import com.github.kr328.clash.core.model.ProxySort

/**
 * 「自动选择最快节点」的共享实现（首页与节点页共用）。
 *
 * 只考虑**测速通过、延迟有效**的节点：
 *  - 排除嵌套的节点组
 *  - 排除从未测速（delay == 0）
 *  - 排除超时 / 不可用（mihomo 用 uint16 最大值 0xffff 表示，即 65535）
 *  - 优先选延迟 <= [PREFERRED_MAX_DELAY] 的节点，实在没有才退而求其次
 *
 * 一次测速可能因网络抖动失败，所以出现「没有任何可用节点」时会自动再测一次。
 */
sealed class AutoSelectResult {
    /** 已切换到 [node]，延迟 [delay] 毫秒 */
    data class Selected(val group: String, val node: String, val delay: Int) : AutoSelectResult()

    /** [group] 是 URLTest / Fallback 这类由内核自动选择的组，无法手动指定 */
    data class Managed(val group: String) : AutoSelectResult()

    /** [group] 里没有任何可用节点 */
    data class NoNode(val group: String) : AutoSelectResult()

    /** 当前没有可用的节点组（例如没有加载订阅，或处于直连模式） */
    object NoGroup : AutoSelectResult()
}

/** mihomo 用 uint16 最大值表示超时/不可用 */
private const val TIMEOUT_DELAY = 0xffff

/** 3 秒以内认为「既可用又够快」 */
private const val PREFERRED_MAX_DELAY = 3000

/** 组自动选择（URLTest / Fallback 等）时 patchSelector 会失败 */
suspend fun autoSelectFastestNode(
    excludeNotSelectable: Boolean,
    group: String? = null,
): AutoSelectResult {
    // 指定了节点组就用它（节点页里用户正在看的那个）；否则取第一个
    // Global 模式下第一项就是 GLOBAL 组，其余情况是第一个可选择的节点组
    val target = if (!group.isNullOrBlank()) {
        group
    } else {
        try {
            withClash {
                queryProxyGroupNames(excludeNotSelectable)
            }.firstOrNull()
        } catch (e: Exception) {
            null
        }
    } ?: return AutoSelectResult.NoGroup

    var candidates = healthCheckAndCollect(target)

    if (candidates.isEmpty()) {
        candidates = healthCheckAndCollect(target)
    }

    if (candidates.isEmpty()) {
        return AutoSelectResult.NoNode(target)
    }

    val preferred = candidates.filter { it.second <= PREFERRED_MAX_DELAY }
    val best = (preferred.ifEmpty { candidates }).minByOrNull { it.second }
        ?: return AutoSelectResult.NoNode(target)

    val patched = try {
        withClash { patchSelector(target, best.first) }
    } catch (e: Exception) {
        false
    }

    if (!patched) {
        return AutoSelectResult.Managed(target)
    }

    return AutoSelectResult.Selected(target, best.first, best.second)
}

/**
 * 对 [group] 做一次测速，并返回「可用且延迟有效」的节点（延迟升序排列）。
 */
suspend fun healthCheckAndCollect(group: String): List<Pair<String, Int>> {
    return try {
        withClash {
            healthCheck(group)
        }

        withClash {
            queryProxyGroup(group, ProxySort.Delay)
        }.proxies
            .asSequence()
            .filter { !it.isGroup }
            .filter { it.delay in 1 until TIMEOUT_DELAY }
            .map { it.name to it.delay }
            .sortedBy { it.second }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}