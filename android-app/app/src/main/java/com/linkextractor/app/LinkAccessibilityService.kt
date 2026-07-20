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
        /** Broadcast action sent when a link is detected */
        const val ACTION_LINK_DETECTED = "com.linkextractor.app.LINK_DETECTED"
        const val EXTRA_LINKS = "links"

        /** Shared instance reference (set in onServiceConnected) */
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

        // Track package change → clear old links
        if (pkg != currentPackage) {
            currentPackage = pkg
            detectedLinks.clear()
        }

        // Collect text from all nodes in the window
        val root = rootInActiveWindow ?: return
        try {
            collectLinksFromNode(root)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() { /* required override */ }

    // ── Link Collection ────────────────────────────────────────────────────────

    private fun collectLinksFromNode(node: AccessibilityNodeInfo) {
        // Check this node's text
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        for (source in listOf(text, contentDesc, viewId)) {
            URL_REGEX.findAll(source).forEach { match ->
                val url = match.value.trimEnd('/', '.', ',', ')')
                detectedLinks.add(url)
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLinksFromNode(child)
            child.recycle()
        }
    }

    // ── Public API (called by FloatingWindowService) ───────────────────────────

    /**
     * Returns all currently detected links and broadcasts them.
     */
    fun extractCurrentLinks(): List<String> {
        val root = rootInActiveWindow
        if (root != null) {
            detectedLinks.clear()
            try { collectLinksFromNode(root) } finally { root.recycle() }
        }

        val links = detectedLinks.toList()

        // Broadcast so FloatingWindowService / MainActivity can receive them
        val intent = Intent(ACTION_LINK_DETECTED).apply {
            setPackage(packageName)
            putStringArrayListExtra(EXTRA_LINKS, ArrayList(links))
        }
        sendBroadcast(intent)

        return links
    }
}
