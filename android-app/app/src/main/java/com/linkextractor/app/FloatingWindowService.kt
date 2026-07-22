package com.linkextractor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var screenW = 0
    private var screenH = 0

    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        PrefsManager.setServiceActive(this, true)
        resolveScreenSize()
        // Shizuku UserService را از قبل bind می‌کنیم تا اولین استخراج سریع‌تر باشد
        ShizukuHelper.bindAndRun { /* ready */ }
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        PrefsManager.setServiceActive(this, false)
        removeFloatingButton()
        job.cancel()
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
            .setOnClickListener {
                FloatingWindowService.stop(this)
            }

        windowManager.addView(view, params)
    }

    private fun removeFloatingButton() {
        floatingView?.let {
            runCatching { windowManager.removeView(it) }
        }
        floatingView = null
    }

    // ── Drag ──────────────────────────────────────────────────────────────────

    private fun setupDrag(view: View) {
        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startParamX - (event.rawX - startX)).toInt()
                        .coerceIn(0, screenW)
                    params.y = (startParamY + (event.rawY - startY)).toInt()
                        .coerceIn(0, screenH)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    // ── Extraction (main logic) ────────────────────────────────────────────────

    /**
     * کاربر دکمه استخراج را زد:
     *  1. از AccessibilityService استفاده می‌کند اگر فعال باشد (fallback سریع)
     *  2. در غیر این‌صورت از Shizuku screencap + OCR استفاده می‌کند
     */
    private fun performExtraction() {
        // روش اول: AccessibilityService (اگر موجود باشد — fallback)
        val accessService = LinkAccessibilityService.instance
        if (accessService != null) {
            val links = runCatching { accessService.extractCurrentLinks() }.getOrDefault(emptyList())
            if (links.isNotEmpty()) {
                handleLinks(links)
                return
            }
        }

        // بررسی وضعیت Shizuku
        if (!ShizukuHelper.isRunning() || !ShizukuHelper.hasPermission()) {
            Toast.makeText(
                this,
                "Shizuku فعال نیست.\nبرنامه اصلی را باز کنید و Shizuku را فعال کنید.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        btnExtract?.isEnabled = false
        btnExtract?.text = "⏳"

        // اطمینان از bind بودن UserService سپس اجرای OCR
        ShizukuHelper.bindAndRun { bound ->
            if (!bound) {
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@FloatingWindowService, "اتصال به Shizuku ناموفق بود", Toast.LENGTH_SHORT).show()
                    btnExtract?.isEnabled = true
                    btnExtract?.text = "🔗 استخراج"
                }
                return@bindAndRun
            }
            serviceScope.launch {
                val links = try {
                    ScreenLinkExtractor(this@FloatingWindowService).extractLinks()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FloatingWindowService, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                        btnExtract?.isEnabled = true
                        btnExtract?.text = "🔗 استخراج"
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    btnExtract?.isEnabled = true
                    btnExtract?.text = "🔗 استخراج"
                    if (links.isEmpty()) {
                        Toast.makeText(this@FloatingWindowService, getString(R.string.no_link_found), Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    handleLinks(links)
                }
            }
        }
    }

    private fun handleLinks(links: List<String>) {
        links.forEach { PrefsManager.addLink(this, it) }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", links[0]))

        // اطلاع‌رسانی به MainActivity برای به‌روزرسانی لیست
        val intent = Intent(LinkAccessibilityService.ACTION_LINK_DETECTED).apply {
            setPackage(packageName)
            putStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS, ArrayList(links))
        }
        sendBroadcast(intent)

        val msg = if (links.size == 1)
            "✓ لینک کپی شد:\n${links[0].take(65)}"
        else
            "✓ ${links.size} لینک پیدا شد — اولین لینک کپی شد"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
