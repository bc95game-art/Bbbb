package com.linkextractor.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that monitors selected apps for HTTP/HTTPS URLs,
 * WebView content URLs, and Intent-based deep-links.
 */
class LinkAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_LINK_DETECTED = "com.linkextractor.app.LINK_DETECTED"
        const val EXTRA_LINKS = "links"

        var instance: LinkAccessibilityService? = null

        private val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>]+|[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'<>]+)",
            RegexOption.IGNORE_CASE
        )
    }

    private val detectedLinks = mutableSetOf<String>()
    private var currentPackage: String = ""

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = (
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = (
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        )
        info.notificationTimeout = 100
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── Event Handling ─────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        val selectedApps = PrefsManager.getSelectedApps(this)
        if (selectedApps.isNotEmpty() && pkg !in selectedApps) return

        if (pkg != currentPackage) {
            currentPackage = pkg
            detectedLinks.clear()
        }

        @Suppress("DEPRECATION")
        val root = rootInActiveWindow ?: return
        try {
            collectLinksFromNode(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() { /* required */ }

    // ── Link Collection ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun collectLinksFromNode(node: AccessibilityNodeInfo) {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        for (source in listOf(text, contentDesc, viewId)) {
            URL_REGEX.findAll(source).forEach { match ->
                val url = match.value.trimEnd('/', '.', ',', ')')
                detectedLinks.add(url)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLinksFromNode(child)
            child.recycle()
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun extractCurrentLinks(): List<String> {
        @Suppress("DEPRECATION")
        val root = rootInActiveWindow
        if (root != null) {
            detectedLinks.clear()
            try {
                collectLinksFromNode(root)
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }

        val links = detectedLinks.toList()

        val intent = Intent(ACTION_LINK_DETECTED).apply {
            setPackage(packageName)
            putStringArrayListExtra(EXTRA_LINKS, ArrayList(links))
        }
        sendBroadcast(intent)

        return links
    }
}
