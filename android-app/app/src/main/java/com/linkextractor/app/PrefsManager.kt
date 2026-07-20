package com.linkextractor.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe preferences manager.
 * Uses commit() for link history to guarantee persistence before the process dies.
 */
object PrefsManager {

    private const val PREF_NAME = "link_extractor_prefs"
    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_LINK_HISTORY  = "link_history"
    private const val KEY_SERVICE_ACTIVE = "service_active"
    private const val SEPARATOR = "\u001F"   // Unit Separator — never appears in URLs
    private const val MAX_HISTORY = 100

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Selected Apps ──────────────────────────────────────────────────────────

    fun getSelectedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()

    fun setSelectedApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    // ── Link History ───────────────────────────────────────────────────────────

    @Synchronized
    fun getLinkHistory(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LINK_HISTORY, "") ?: ""
        return raw
            .split(SEPARATOR)
            .filter { it.isNotBlank() && it.startsWith("http", ignoreCase = true)
                    || (it.contains("://") && it.length > 10) }
    }

    @Synchronized
    fun addLink(context: Context, link: String) {
        val trimmed = link.trim()
        if (trimmed.isBlank() || trimmed.length < 10) return

        val current = getLinkHistory(context).toMutableList()
        current.remove(trimmed)          // remove duplicate → move to top
        current.add(0, trimmed)
        val final = current.take(MAX_HISTORY)

        // commit() — synchronous; guarantees the write before the process dies
        prefs(context).edit()
            .putString(KEY_LINK_HISTORY, final.joinToString(SEPARATOR))
            .commit()
    }

    @Synchronized
    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_LINK_HISTORY).commit()
    }

    // ── Service state (so MainActivity can reflect it after restart) ───────────

    fun setServiceActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ACTIVE, active).apply()
    }

    fun isServiceActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ACTIVE, false)
}
