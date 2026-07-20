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
 * Foreground service that displays a draggable floating button
 * overlay on top of all other apps.
 */
class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "link_extractor_channel"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

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

        // ── Drag support ──────────────────────────────────────────────────────
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        floatingView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // ── Extract button ────────────────────────────────────────────────────
        floatingView!!.findViewById<ExtendedFloatingActionButton>(R.id.btnExtract)
            .setOnClickListener {
                performExtraction()
            }

        // ── Close button ──────────────────────────────────────────────────────
        floatingView!!.findViewById<FloatingActionButton>(R.id.btnClose)
            .setOnClickListener {
                stopSelf()
            }

        windowManager.addView(floatingView, params)
    }

    private fun removeFloatingButton() {
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
    }

    // ── Extraction ─────────────────────────────────────────────────────────────

    private fun performExtraction() {
        val service = LinkAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "سرویس دسترس‌پذیری فعال نیست", Toast.LENGTH_SHORT).show()
            return
        }

        val links = service.extractCurrentLinks()

        if (links.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_link_found), Toast.LENGTH_SHORT).show()
            return
        }

        // Save each link
        links.forEach { PrefsManager.addLink(this, it) }

        // Copy first (most relevant) link to clipboard
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("extracted_link", links[0]))

        val msg = if (links.size == 1) {
            "✓ لینک کپی شد:\n${links[0].take(60)}"
        } else {
            "✓ ${links.size} لینک پیدا شد! اولین لینک کپی شد."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Broadcast Receiver ─────────────────────────────────────────────────────

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Links are handled via Toast in performExtraction()
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
        ).apply {
            description = "نمایش وضعیت سرویس شناور"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("استخراج لینک")
            .setContentText("سرویس فعال است — دکمه شناور روی برنامه‌ها نمایش داده می‌شود")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
