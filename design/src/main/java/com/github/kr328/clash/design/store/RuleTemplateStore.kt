package com.github.kr328.clash.design.store

import android.content.Context

/**
 * Stores user created rule templates in SharedPreferences.
 *
 * One preference key per template keeps this simple and avoids any extra
 * serialization dependency: the value is the rules joined by newlines.
 */
class RuleTemplateStore(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun names(): List<String> =
        preferences.all.keys
            .filter { it.startsWith(PREFIX) }
            .map { it.substring(PREFIX.length) }
            .filter { it.isNotBlank() }
            .sorted()

    fun load(name: String): List<String> =
        preferences.getString(PREFIX + name, null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun save(name: String, rules: List<String>) {
        preferences.edit().putString(PREFIX + name, rules.joinToString("\n")).apply()
    }

    fun delete(name: String) {
        preferences.edit().remove(PREFIX + name).apply()
    }

    private companion object {
        const val NAME = "rule_templates"
        const val PREFIX = "template:"
    }
}