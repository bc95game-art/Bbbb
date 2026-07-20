package com.linkextractor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Intercepts public broadcasts that carry URLs:
 *  - ACTION_VIEW  (http / https / deep-links)
 *  - ACTION_SEND  (shared text that may contain URLs)
 *  - ACTION_SENDTO
 *
 * Note: Android's security model prevents intercepting *private* intents between
 * two apps. This receiver captures intents that are sent as public broadcasts or
 * passed through the system with implicit intent-filters we declared.
 */
class IntentMonitorReceiver : BroadcastReceiver() {

    companion object {
        private val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>]+|[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'<>]+)",
            RegexOption.IGNORE_CASE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val links = mutableSetOf<String>()

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Direct URL intent (e.g. app opens a browser link)
                intent.data?.let { uri ->
                    val url = uri.toString()
                    if (url.startsWith("http://") || url.startsWith("https://") ||
                        url.contains("://")
                    ) {
                        links.add(url)
                    }
                }
            }

            Intent.ACTION_SEND -> {
                // Shared text that may contain a URL
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                extractUrls(text + " " + subject, links)
                // Also check data URI
                intent.data?.toString()?.let { if (it.contains("://")) links.add(it) }
            }

            Intent.ACTION_SENDTO -> {
                intent.data?.toString()?.let { extractUrls(it, links) }
            }

            // Custom broadcast from our own AccessibilityService
            LinkAccessibilityService.ACTION_LINK_DETECTED -> {
                val received = intent.getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                received?.forEach { links.add(it) }
            }
        }

        // Persist all found links
        links.filter { it.length > 8 }.forEach { link ->
            PrefsManager.addLink(context, link.trimEnd('/', '.', ',', ')'))
        }

        // Forward to FloatingWindowService if running
        if (links.isNotEmpty()) {
            val notifyIntent = Intent(LinkAccessibilityService.ACTION_LINK_DETECTED).apply {
                setPackage(context.packageName)
                putStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS, ArrayList(links))
            }
            context.sendBroadcast(notifyIntent)
        }
    }

    private fun extractUrls(text: String, into: MutableSet<String>) {
        URL_REGEX.findAll(text).forEach { into.add(it.value.trimEnd('/', '.', ',', ')')) }
    }
}
