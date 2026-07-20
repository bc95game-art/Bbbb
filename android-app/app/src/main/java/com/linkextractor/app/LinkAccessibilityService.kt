package com.linkextractor.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Core accessibility service.
 *
 * Captures links from three sources:
 *  1. Text / content-description / viewId on any visible node
 *  2. WebView address-bar nodes (class = android.webkit.WebView)
 *  3. Chrome Custom Tabs toolbar URL nodes
 *
 * On every TYPE_WINDOW_STATE_CHANGED (= new Activity / dialog) it triggers a
 * full deep-scan immediately so Intent-launched pages are caught right away.
 */
class LinkAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_LINK_DETECTED = "com.linkextractor.app.LINK_DETECTED"
        const val EXTRA_LINKS = "links"

        var instance: LinkAccessibilityService? = null

        // Matches http/https and any deep-link scheme (e.g. shad://, intent://)
        val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>\\[\\]{}|\\\\^`]+|[a-zA-Z][a-zA-Z0-9+.\\-]{2,}://[^\\s\"'<>\\[\\]{}|\\\\^`]+)",
            RegexOption.IGNORE_CASE
        )

        // WebView / Custom Tab class names that contain a URL bar
        private val WEBVIEW_CLASSES = setOf(
            "android.webkit.WebView",
            "com.android.chrome",
            "org.chromium.chrome"
        )
        private val URL_BAR_IDS = setOf(
            "url_bar", "location_bar_edit_text", "url_field",
            "addressbar", "address_bar", "omnibox"
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
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        )
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = (
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        )
        info.notificationTimeout = 50
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

        // New app / activity → reset link collection
        if (pkg != currentPackage) {
            currentPackage = pkg
            detectedLinks.clear()
        }

        when (event.eventType) {
            // New window / activity opened → deep scan immediately
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                detectedLinks.clear()
                scanWindowNow()
                // Also grab links from the event's own text (window title etc.)
                event.text.forEach { extractUrls(it?.toString() ?: "", detectedLinks) }
            }

            // Content changed → lighter incremental scan
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scanWindowNow()
            }

            // URL bar typing in browsers
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val nodeClass = event.className?.toString() ?: ""
                val text = event.text.joinToString(" ")
                if (isUrlBarEvent(event) || URL_REGEX.containsMatchIn(text)) {
                    extractUrls(text, detectedLinks)
                }
            }
        }
    }

    override fun onInterrupt() { /* required */ }

    // ── Deep Scan ──────────────────────────────────────────────────────────────

    private fun scanWindowNow() {
        @Suppress("DEPRECATION")
        val root = rootInActiveWindow ?: return
        try {
            collectFromNode(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectFromNode(node: AccessibilityNodeInfo) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // ── WebView: extract URL from node extras (API 26+) ──────────────────
        if (className == "android.webkit.WebView") {
            val extras: Bundle? = node.extras
            extras?.getString("url")?.let { extractUrls(it, detectedLinks) }
        }

        // ── URL bar in Chrome / Custom Tabs ───────────────────────────────────
        val idPart = viewId.substringAfterLast("/").lowercase()
        if (idPart in URL_BAR_IDS) {
            extractUrls(text, detectedLinks)
        }

        // ── General text / desc / viewId scan ─────────────────────────────────
        for (source in listOf(text, desc, viewId)) {
            extractUrls(source, detectedLinks)
        }

        // ── Recurse into children ─────────────────────────────────────────────
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectFromNode(child)
            child.recycle()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun extractUrls(text: String, into: MutableSet<String>) {
        if (text.isBlank()) return
        URL_REGEX.findAll(text).forEach { match ->
            val url = match.value.trimEnd('/', '.', ',', ')', ']', '}')
            if (url.length > 10) into.add(url)
        }
    }

    private fun isUrlBarEvent(event: AccessibilityEvent): Boolean {
        val viewId = event.source?.viewIdResourceName?.substringAfterLast("/")?.lowercase() ?: ""
        return viewId in URL_BAR_IDS
    }

    // ── Public API (called by FloatingWindowService) ───────────────────────────

    /**
     * Triggers a fresh window scan and returns all collected links.
     * Also broadcasts the results so MainActivity can refresh.
     */
    fun extractCurrentLinks(): List<String> {
        scanWindowNow()
        val links = detectedLinks.toList()

        val intent = Intent(ACTION_LINK_DETECTED).apply {
            setPackage(packageName)
            putStringArrayListExtra(EXTRA_LINKS, ArrayList(links))
        }
        sendBroadcast(intent)

        return links
    }

    /**
     * Returns the live set without triggering a new scan.
     * Useful for auto-capture checks.
     */
    fun peekLinks(): Set<String> = detectedLinks.toSet()
}
