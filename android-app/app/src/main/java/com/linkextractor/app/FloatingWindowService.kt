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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "link_extractor_channel"
        const val NOTIF_ID   = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var floatingView: View? = null
    private var btnExtract: ExtendedFloatingActionButton? = null
    private var autoCapturedCount = 0
    private var screenW = 0
    private var screenH = 0

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        PrefsManager.setServiceActive(this, true)
        resolveScreenSize()
        registerLinkReceiver()
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        PrefsManager.setServiceActive(this, false)
        removeFloatingButton()
        try { unregisterReceiver(linkReceiver) } catch (_: Exception) {}
    }

    // ── Screen size ────────────────────────────────────────────────────────────

    private fun resolveScreenSize() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    // ── Floating Button ────────────────────────────────────────────────────────

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
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

        val view = try {
            LayoutInflater.from(this).inflate(R.layout.floating_window, null)
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در نمایش دکمه شناور", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        floatingView = view
        btnExtract   = view.findViewById(R.id.btnExtract)

        setupDrag(view)

        view.findViewById<ExtendedFloatingActionButton>(R.id.btnExtract)
            .setOnClickListener { performExtraction() }

        view.findViewById<FloatingActionButton>(R.id.btnClose)
            .setOnClickListener { stopSelf() }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // Overlay permission was revoked while we were starting
            Toast.makeText(this, "مجوز نمایش روی برنامه‌ها لازم است", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun setupDrag(view: View) {
        var initX = 0; var initY = 0
        var initTX = 0f; var initTY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = event.rawX; initTY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initTX - event.rawX).toInt()
                    val dy = (event.rawY - initTY).toInt()
                    if (dx * dx + dy * dy > 25) dragged = true

                    // Clamp inside screen boundaries so button never goes off-screen
                    params.x = (initX + dx).coerceIn(0, screenW / 2)
                    params.y = (initY + dy).coerceIn(0, screenH - 300)

                    try { windowManager.updateViewLayout(floatingView, params) }
                    catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Snap to nearest edge for clean look
                    if (dragged) snapToEdge()
                    false    // let onClick fire if not dragged
                }
                else -> false
            }
        }
    }

    /** Animate the button to the nearest left/right edge after drag ends. */
    private fun snapToEdge() {
        params.x = if (params.x < screenW / 4) 24 else 24
        try { windowManager.updateViewLayout(floatingView, params) }
        catch (_: Exception) {}
    }

    private fun removeFloatingButton() {
        val v = floatingView ?: return
        try { windowManager.removeView(v) } catch (_: Exception) {}
        floatingView = null
        btnExtract   = null
    }

    // ── Auto-capture badge ─────────────────────────────────────────────────────

    private fun onAutoCapture(links: List<String>) {
        autoCapturedCount += links.size
        val btn = btnExtract ?: return
        btn.text = if (autoCapturedCount > 0) "🔗 استخراج ($autoCapturedCount)" else "🔗 استخراج"
    }

    // ── Manual Extraction ──────────────────────────────────────────────────────

    private fun performExtraction() {
        val service = LinkAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "⚠ سرویس دسترس‌پذیری فعال نیست.\nبرنامه را باز کنید و آن را فعال کنید.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val links = try {
            service.extractCurrentLinks()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در استخراج: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        if (links.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_link_found), Toast.LENGTH_SHORT).show()
            return
        }

        links.forEach { PrefsManager.addLink(this, it) }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", links[0]))

        autoCapturedCount = 0
        btnExtract?.text  = "🔗 استخراج"

        val msg = if (links.size == 1)
            "✓ لینک کپی شد:\n${links[0].take(65)}"
        else
            "✓ ${links.size} لینک پیدا شد — اولین لینک کپی شد"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Broadcast ──────────────────────────────────────────────────────────────

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val links = intent
                ?.getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                ?: return
            if (links.isNotEmpty()) onAutoCapture(links)
        }
    }

    private fun registerLinkReceiver() {
        registerReceiver(
            linkReceiver,
            IntentFilter(LinkAccessibilityService.ACTION_LINK_DETECTED),
            RECEIVER_NOT_EXPORTED
        )
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "سرویس استخراج لینک", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "وضعیت سرویس شناور" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("استخراج لینک — فعال")
            .setContentText("دکمه شناور روی همه برنامه‌ها در دسترس است")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
