package com.linkextractor.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.linkextractor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var linkAdapter: LinkAdapter

    // ── Activity Results ───────────────────────────────────────────────────────

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateAllUI() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateAllUI() }

    private val appSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateSelectedAppsUI() }

    // ── Broadcast receiver ─────────────────────────────────────────────────────

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
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
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
     * Xiaomi/MIUI bug: Settings.canDrawOverlays() returns false even after
     * the user grants the permission. Workaround: attempt to actually add a
     * 0-sized overlay window — if it succeeds the permission is truly granted.
     */
    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(this)) return true

        // MIUI workaround — try creating a real overlay
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

    private fun hasAccessibilityPermission(): Boolean {
        return try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    // ── Permission Request Helpers ─────────────────────────────────────────────

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) {
            Snackbar.make(binding.root, "مجوز از قبل فعال است ✓", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (isMiui()) {
            // MIUI/HyperOS: standard ACTION_MANAGE_OVERLAY_PERMISSION often doesn't work.
            // The correct place is MIUI Security Center → App permissions → Other permissions.
            MaterialAlertDialogBuilder(this)
                .setTitle("مجوز نمایش شناور — Xiaomi")
                .setMessage(
                    "روی Xiaomi باید از مرکز امنیت MIUI این مجوز را بدهید:\n\n" +
                    "۱. دکمه «باز کردن مرکز امنیت» را بزنید\n" +
                    "۲. در لیست، روی «استخراج لینک» ضربه بزنید\n" +
                    "۳. گزینه «سایر مجوزها» را باز کنید\n" +
                    "۴. «نمایش پنجره‌های شناور» را روشن کنید\n" +
                    "۵. «باز کردن پنجره‌ها در پس‌زمینه» را هم روشن کنید\n\n" +
                    "اگر مرکز امنیت باز نشد، دکمه «تنظیمات استاندارد» را بزنید."
                )
                .setPositiveButton("باز کردن مرکز امنیت") { _, _ -> launchMiuiPermissionEditor() }
                .setNeutralButton("تنظیمات استاندارد") { _, _ -> launchOverlaySettings() }
                .setNegativeButton("لغو", null)
                .show()
        } else {
            launchOverlaySettings()
        }
    }

    /** Opens MIUI Security Center directly on the app's permission page. */
    private fun launchMiuiPermissionEditor() {
        val launched = runCatching {
            overlayLauncher.launch(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", packageName)
                }
            )
        }.isSuccess
        if (!launched) launchOverlaySettings()
    }

    private fun launchOverlaySettings() {
        runCatching {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            Toast.makeText(this, "صفحه تنظیمات باز نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        val isMiuiDevice = isMiui()
        val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        if (isMiuiDevice && isAndroid13Plus) {
            // MIUI + Android 13+: "Allow restricted settings" toggle is GREYED OUT by Xiaomi.
            // The only reliable solution without root is ADB.
            showMiuiAdbDialog()
        } else if (isAndroid13Plus) {
            // Stock Android 13+: toggle exists in App Info → ⋮ menu
            MaterialAlertDialogBuilder(this)
                .setTitle("فعال‌سازی سرویس دسترس‌پذیری")
                .setMessage(
                    "⚠ مرحله اول — اجازه تنظیمات محدود (Android 13+)\n\n" +
                    "۱. دکمه «اطلاعات برنامه» را بزنید\n" +
                    "۲. روی آیکون ⋮ (سه‌نقطه بالا-راست) ضربه بزنید\n" +
                    "۳. «Allow restricted settings» را انتخاب کنید\n" +
                    "۴. برگردید و دکمه «باز کردن دسترس‌پذیری» را بزنید"
                )
                .setPositiveButton("باز کردن دسترس‌پذیری") { _, _ -> launchAccessibilitySettings() }
                .setNeutralButton("اطلاعات برنامه") { _, _ -> launchAppInfo() }
                .setNegativeButton("لغو", null)
                .show()
        } else if (isMiuiDevice) {
            MaterialAlertDialogBuilder(this)
                .setTitle("راهنمای Xiaomi / MIUI")
                .setMessage(
                    "۱. وارد «تنظیمات دسترس‌پذیری» می‌شوید\n" +
                    "۲. روی «برنامه‌های بارگیری‌شده» ضربه بزنید\n" +
                    "۳. «سرویس استخراج لینک» را پیدا و فعال کنید"
                )
                .setPositiveButton("باز کردن دسترس‌پذیری") { _, _ -> launchAccessibilitySettings() }
                .setNegativeButton("لغو", null)
                .show()
        } else {
            launchAccessibilitySettings()
        }
    }

    /**
     * MIUI + Android 13+: Xiaomi disables the "Allow restricted settings" toggle for
     * sideloaded APKs. Show options: LADB (no PC), or classic ADB with PC.
     */
    private fun showMiuiAdbDialog() {
        val serviceComponent =
            "$packageName/com.linkextractor.app.LinkAccessibilityService"
        val adbCommand =
            "settings put secure enabled_accessibility_services $serviceComponent"

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠ Xiaomi — تنظیمات دسترس‌پذیری قفل است")
            .setMessage(
                "شیومی این سرویس را برای APK‌های sideload مسدود می‌کند.\n\n" +
                "━━━━━━━━━━━━━━━\n" +
                "✅ روش ۱ — بدون کامپیوتر (LADB)\n\n" +
                "۱. «برنامه‌نویسی» را در تنظیمات فعال کنید:\n" +
                "   تنظیمات ← درباره گوشی ← ۷ بار روی «نسخه MIUI» بزنید\n\n" +
                "۲. بروید: تنظیمات ← گزینه‌های توسعه‌دهنده\n" +
                "   «اشکال‌زدایی بی‌سیم» را روشن کنید\n\n" +
                "۳. برنامه LADB را از پلی‌استور نصب کنید\n\n" +
                "۴. LADB را باز کنید، «Pair» را بزنید، کد و پورت را از\n" +
                "   «اشکال‌زدایی بی‌سیم» وارد کنید\n\n" +
                "۵. دکمه «کپی دستور» را بزنید، در LADB جای‌گذاری و اجرا کنید\n\n" +
                "━━━━━━━━━━━━━━━\n" +
                "روش ۲ — با کامپیوتر:\n" +
                "دستور را در CMD اجرا کنید: adb shell " + adbCommand
            )
            .setPositiveButton("باز کردن دسترس‌پذیری") { _, _ -> launchAccessibilitySettings() }
            .setNeutralButton("کپی دستور") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("ADB command", adbCommand)
                )
                Toast.makeText(this, "دستور کپی شد — در LADB جای‌گذاری کنید ✓", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun launchAppInfo() {
        runCatching {
            accessibilityLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            Toast.makeText(this, "صفحه اطلاعات برنامه باز نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchAccessibilitySettings() {
        runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "صفحه تنظیمات باز نشد", Toast.LENGTH_SHORT).show()
        }
    }

    // ── MIUI Detection ─────────────────────────────────────────────────────────

    private fun isMiui(): Boolean = try {
        val prop = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        val version = prop.invoke(null, "ro.miui.ui.version.name", "") as String
        version.isNotEmpty()
    } catch (_: Exception) {
        false
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
        val overlayOk = hasOverlayPermission()
        val accessOk  = hasAccessibilityPermission()

        with(binding) {
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

            if (accessOk) {
                tvAccessibilityStatus.text = getString(R.string.permission_accessibility_ok)
                tvAccessibilityStatus.setTextColor(getColor(R.color.green))
                btnAccessibility.isEnabled = false
                btnAccessibility.alpha     = 0.5f
            } else {
                tvAccessibilityStatus.text = getString(R.string.permission_accessibility_no)
                tvAccessibilityStatus.setTextColor(getColor(R.color.red))
                btnAccessibility.isEnabled = true
                btnAccessibility.alpha     = 1f
            }

            btnStartService.isEnabled = overlayOk && accessOk
            btnStartService.alpha     = if (overlayOk && accessOk) 1f else 0.5f
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
            !hasAccessibilityPermission() -> Snackbar
                .make(binding.root, "ابتدا سرویس دسترس‌پذیری را فعال کنید", Snackbar.LENGTH_LONG)
                .setAction("فعال‌سازی") { openAccessibilitySettings() }
                .show()
            else -> {
                FloatingWindowService.start(this)
                updateServiceStatus(active = true)
                Snackbar.make(
                    binding.root,
                    "✓ سرویس شروع شد — برنامه را ببندید و وارد برنامه هدف شوید",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
