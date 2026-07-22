package com.linkextractor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.linkextractor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var linkAdapter: LinkAdapter

    // ── Activity Results ───────────────────────────────────────────────────────

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateAllUI() }

    /** درخواست مجوز ضبط صفحه از کاربر */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            FloatingWindowService.projectionResultCode = result.resultCode
            FloatingWindowService.projectionData       = result.data
            updateAllUI()
            Snackbar.make(binding.root, "✓ مجوز ضبط صفحه داده شد", Snackbar.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "مجوز ضبط صفحه رد شد", Toast.LENGTH_LONG).show()
        }
    }

    private val appSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateSelectedAppsUI() }

    // ── Broadcast Receiver ─────────────────────────────────────────────────────

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val links = intent
                ?.getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                ?: return
            links.forEach { PrefsManager.addLink(this@MainActivity, it) }
            refreshLinkHistory()
            if (links.isNotEmpty()) {
                Snackbar.make(
                    binding.root,
                    "🔗 ${links.size} لینک جدید ثبت شد",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAllUI()
        registerReceiver(
            linkReceiver,
            IntentFilter(LinkAccessibilityService.ACTION_LINK_DETECTED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(linkReceiver) }
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        linkAdapter = LinkAdapter(mutableListOf()).apply {
            onLinkCopied = { link ->
                Snackbar.make(binding.root, "✓ لینک کپی شد", Snackbar.LENGTH_SHORT)
                    .setAction("اشتراک") {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }, "اشتراک‌گذاری"))
                    }.show()
            }
        }
        binding.rvLinks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = linkAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupClickListeners() {
        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnScreenCapture.setOnClickListener { requestScreenCapturePermission() }
        binding.btnSelectApps.setOnClickListener {
            appSelectorLauncher.launch(Intent(this, AppSelectorActivity::class.java))
        }
        binding.btnStartService.setOnClickListener { startFloatingService() }
        binding.btnStopService.setOnClickListener {
            FloatingWindowService.stop(this)
            updateServiceStatus(active = false)
            Snackbar.make(binding.root, "سرویس متوقف شد", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnClearHistory.setOnClickListener {
            PrefsManager.clearHistory(this)
            refreshLinkHistory()
            Snackbar.make(binding.root, "تاریخچه پاک شد", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Permission Checks ──────────────────────────────────────────────────────

    /**
     * بررسی مجوز نمایش روی برنامه‌ها.
     * Workaround برای باگ MIUI که canDrawOverlays() همیشه false برمی‌گرداند.
     */
    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(this)) return true
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v  = View(this)
            val lp = WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            wm.removeView(v)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * بررسی اینکه مجوز ضبط صفحه داده شده است.
     * توکن در FloatingWindowService.companion ذخیره می‌شود.
     */
    private fun hasScreenCapturePermission(): Boolean {
        return FloatingWindowService.projectionResultCode != 0 &&
               FloatingWindowService.projectionData != null
    }

    // ── Permission Requests ────────────────────────────────────────────────────

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
        )
    }

    /**
     * باز کردن دیالوگ سیستمی ضبط صفحه.
     * یک پنجره ساده «آیا اجازه می‌دهید این برنامه صفحه را ضبط کند؟» نشان می‌دهد.
     */
    private fun requestScreenCapturePermission() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ── UI Updates ─────────────────────────────────────────────────────────────

    private fun updateAllUI() {
        updatePermissionUI()
        updateSelectedAppsUI()
        refreshLinkHistory()
        val active = PrefsManager.isServiceActive(this)
        updateServiceStatus(active)
    }

    private fun updatePermissionUI() {
        val overlayOk       = hasOverlayPermission()
        val screenCaptureOk = hasScreenCapturePermission()

        with(binding) {
            // Overlay row
            if (overlayOk) {
                tvOverlayStatus.text = getString(R.string.permission_overlay_ok)
                tvOverlayStatus.setTextColor(getColor(R.color.green))
                btnOverlay.isEnabled = false
                btnOverlay.alpha     = 0.5f
            } else {
                tvOverlayStatus.text = getString(R.string.permission_overlay_no)
                tvOverlayStatus.setTextColor(getColor(R.color.red))
                btnOverlay.isEnabled = true
                btnOverlay.alpha     = 1f
            }

            // Screen capture row
            if (screenCaptureOk) {
                tvScreenCaptureStatus.text = getString(R.string.permission_screen_capture_ok)
                tvScreenCaptureStatus.setTextColor(getColor(R.color.green))
                btnScreenCapture.isEnabled = false
                btnScreenCapture.alpha     = 0.5f
            } else {
                tvScreenCaptureStatus.text = getString(R.string.permission_screen_capture_no)
                tvScreenCaptureStatus.setTextColor(getColor(R.color.red))
                btnScreenCapture.isEnabled = true
                btnScreenCapture.alpha     = 1f
            }

            // سرویس فقط نیاز به Overlay + ScreenCapture دارد
            btnStartService.isEnabled = overlayOk && screenCaptureOk
            btnStartService.alpha     = if (overlayOk && screenCaptureOk) 1f else 0.5f
        }
    }

    private fun updateSelectedAppsUI() {
        val selected = PrefsManager.getSelectedApps(this)
        binding.tvSelectedApps.text = if (selected.isEmpty())
            getString(R.string.no_apps_selected)
        else
            getString(R.string.selected_apps_count, selected.size)
    }

    fun updateServiceStatus(active: Boolean) {
        with(binding) {
            tvServiceStatus.text = if (active)
                getString(R.string.status_active)
            else
                getString(R.string.status_inactive)
            tvServiceStatus.setTextColor(
                getColor(if (active) R.color.green else R.color.status_idle)
            )
            viewStatusDot.setBackgroundResource(
                if (active) R.drawable.dot_active else R.drawable.dot_idle
            )
            btnStopService.isVisible  = active
            btnStartService.isVisible = !active
        }
    }

    private fun refreshLinkHistory() {
        val links = PrefsManager.getLinkHistory(this)
        linkAdapter.updateLinks(links)
        with(binding) {
            tvEmptyLinks.isVisible    = links.isEmpty()
            rvLinks.isVisible         = links.isNotEmpty()
            btnClearHistory.isVisible = links.isNotEmpty()
        }
    }

    // ── Service Control ────────────────────────────────────────────────────────

    private fun startFloatingService() {
        when {
            !hasOverlayPermission() -> Snackbar
                .make(binding.root, "ابتدا مجوز نمایش روی برنامه‌ها را فعال کنید", Snackbar.LENGTH_LONG)
                .setAction("فعال‌سازی") { requestOverlayPermission() }
                .show()
            !hasScreenCapturePermission() -> Snackbar
                .make(binding.root, "ابتدا مجوز ضبط صفحه را فعال کنید", Snackbar.LENGTH_LONG)
                .setAction("فعال‌سازی") { requestScreenCapturePermission() }
                .show()
            else -> {
                FloatingWindowService.start(this)
                updateServiceStatus(active = true)
                Snackbar.make(
                    binding.root,
                    "✓ سرویس شروع شد — برنامه را ببندید و وارد شاد شوید",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
