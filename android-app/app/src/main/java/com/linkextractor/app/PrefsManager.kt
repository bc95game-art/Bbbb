package com.linkextractor.app

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {

    private const val PREF_NAME = "link_extractor_prefs"
    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_LINK_HISTORY = "link_history"
    private const val MAX_HISTORY = 50

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Selected Apps ──────────────────────────────────────────────────────────

    fun getSelectedApps(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
    }

    fun setSelectedApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    // ── Link History ───────────────────────────────────────────────────────────

    fun getLinkHistory(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LINK_HISTORY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun addLink(context: Context, link: String) {
        val current = getLinkHistory(context).toMutableList()
        if (current.contains(link)) current.remove(link)   // move to top
        current.add(0, link)
        val trimmed = current.take(MAX_HISTORY)
        prefs(context).edit().putString(KEY_LINK_HISTORY, trimmed.joinToString("\n")).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_LINK_HISTORY).apply()
    }
}
