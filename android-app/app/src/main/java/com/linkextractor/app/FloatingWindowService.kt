package com.linkextractor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Foreground service that displays a draggable floating button overlay.
 *
 * The floating button has two modes:
 *  • Manual extract  — user taps "🔗 استخراج" → scans and copies the best link
 *  • Auto-capture    — listens to IntentMonitorReceiver broadcasts and shows a
 *                      badge count when new links arrive automatically
 */
class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "link_extractor_channel"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var btnExtract: ExtendedFloatingActionButton? = null

    // Count of links auto-captured since last user dismiss
    private var autoCapturedCount = 0

    // ── Service Lifecycle ──────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerLinkReceiver()
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingButton()
        try { unregisterReceiver(linkReceiver) } catch (_: Exception) {}
    }

    // ── Floating Button ────────────────────────────────────────────────────────

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)
        btnExtract = floatingView!!.findViewById(R.id.btnExtract)

        // ── Drag support ──────────────────────────────────────────────────────
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false

        floatingView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // ── Extract button ────────────────────────────────────────────────────
        btnExtract!!.setOnClickListener {
            if (!isDragging) performExtraction()
        }

        // ── Close button ──────────────────────────────────────────────────────
        floatingView!!.findViewById<FloatingActionButton>(R.id.btnClose)
            .setOnClickListener { stopSelf() }

        windowManager.addView(floatingView, params)
    }

    private fun removeFloatingButton() {
        floatingView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        floatingView = null
        btnExtract = null
    }

    // ── Auto-capture badge ─────────────────────────────────────────────────────

    /**
     * Called when IntentMonitorReceiver or AccessibilityService captures links
     * automatically (without the user pressing the button).
     */
    private fun onAutoCapture(links: List<String>) {
        autoCapturedCount += links.size
        // Update button label to show count
        val label = if (autoCapturedCount > 0)
            "🔗 استخراج ($autoCapturedCount)"
        else
            "🔗 استخراج"
        btnExtract?.text = label
    }

    // ── Manual Extraction ──────────────────────────────────────────────────────

    private fun performExtraction() {
        val service = LinkAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "سرویس دسترس‌پذیری فعال نیست", Toast.LENGTH_SHORT).show()
            return
        }

        // Merge: manually scanned + auto-captured from intents
        val scanned = service.extractCurrentLinks().toMutableList()
        val history = PrefsManager.getLinkHistory(this)
        // Include the most-recent auto-captured links that aren't already in scanned
        history.take(autoCapturedCount).forEach { if (it !in scanned) scanned.add(0, it) }

        if (scanned.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_link_found), Toast.LENGTH_SHORT).show()
            return
        }

        // Save all
        scanned.forEach { PrefsManager.addLink(this, it) }

        // Copy the first (freshest) link
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("extracted_link", scanned[0]))

        // Reset badge
        autoCapturedCount = 0
        btnExtract?.text = "🔗 استخراج"

        val msg = when {
            scanned.size == 1 -> "✓ لینک کپی شد:\n${scanned[0].take(70)}"
            else -> "✓ ${scanned.size} لینک پیدا شد!\nاولین لینک کپی شد."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Broadcast Receiver ─────────────────────────────────────────────────────

    /**
     * Listens for links captured automatically by IntentMonitorReceiver or
     * the enhanced AccessibilityService window-scan.
     */
    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val links = intent
                ?.getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                ?: return
            if (links.isNotEmpty()) onAutoCapture(links)
        }
    }

    private fun registerLinkReceiver() {
        val filter = IntentFilter(LinkAccessibilityService.ACTION_LINK_DETECTED)
        registerReceiver(linkReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "سرویس استخراج لینک",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "نمایش وضعیت سرویس شناور" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("استخراج لینک")
            .setContentText("سرویس فعال — دکمه شناور روی برنامه‌ها نمایش داده می‌شود")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
