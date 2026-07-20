package com.linkextractor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives shared text (ACTION_SEND) and internal link-detected broadcasts.
 *
 * Note: ACTION_VIEW for http/https was intentionally removed from the manifest
 * so the app does NOT appear as a browser alternative in the system chooser.
 * HTTP/HTTPS links are captured instead via the AccessibilityService.
 */
class IntentMonitorReceiver : BroadcastReceiver() {

    companion object {
        private val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>]+|[a-zA-Z][a-zA-Z0-9+.\\-]{2,}://[^\\s\"'<>]+)",
            RegexOption.IGNORE_CASE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val links = mutableSetOf<String>()

        when (intent.action) {
            // Text shared from another app (e.g. Telegram "Share" button)
            Intent.ACTION_SEND -> {
                val text    = intent.getStringExtra(Intent.EXTRA_TEXT)    ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                extractUrls("$text $subject", links)
            }

            // Internal broadcast from LinkAccessibilityService
            LinkAccessibilityService.ACTION_LINK_DETECTED -> {
                val received = intent
                    .getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                received?.forEach { links.add(it) }
            }
        }

        // Persist all found links
        links
            .filter { it.length > 10 }
            .forEach { PrefsManager.addLink(context, it.trimEnd('/', '.', ',', ')')) }
    }

    private fun extractUrls(text: String, into: MutableSet<String>) {
        if (text.isBlank()) return
        URL_REGEX.findAll(text).forEach {
            val url = it.value.trimEnd('/', '.', ',', ')')
            if (url.length > 10) into.add(url)
        }
    }
}
