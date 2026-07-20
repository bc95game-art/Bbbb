package com.linkextractor.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvSelectedApps: TextView
    private lateinit var tvEmptyLinks: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnSelectApps: MaterialButton
    private lateinit var btnStartService: MaterialButton
    private lateinit var btnStopService: MaterialButton
    private lateinit var btnClearHistory: TextView
    private lateinit var rvLinks: RecyclerView

    private lateinit var linkAdapter: LinkAdapter

    // ── Activity Results ───────────────────────────────────────────────────────
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updatePermissionUI() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updatePermissionUI() }

    private val appSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateSelectedAppsUI() }

    // ── Broadcast receiver for new links ───────────────────────────────────────
    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val links = intent?.getStringArrayListExtra(LinkAccessibilityService.EXTRA_LINKS)
                ?: return
            links.forEach { PrefsManager.addLink(this@MainActivity, it) }
            refreshLinkHistory()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        updateSelectedAppsUI()
        refreshLinkHistory()
        registerReceiver(
            linkReceiver,
            IntentFilter(LinkAccessibilityService.ACTION_LINK_DETECTED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(linkReceiver) } catch (_: Exception) {}
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvSelectedApps = findViewById(R.id.tvSelectedApps)
        tvEmptyLinks = findViewById(R.id.tvEmptyLinks)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        rvLinks = findViewById(R.id.rvLinks)
    }

    private fun setupRecyclerView() {
        linkAdapter = LinkAdapter(mutableListOf())
        rvLinks.layoutManager = LinearLayoutManager(this)
        rvLinks.adapter = linkAdapter
    }

    private fun setupClickListeners() {
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnSelectApps.setOnClickListener {
            appSelectorLauncher.launch(Intent(this, AppSelectorActivity::class.java))
        }
        btnStartService.setOnClickListener { startFloatingService() }
        btnStopService.setOnClickListener {
            FloatingWindowService.stop(this)
            updateServiceStatus(active = false)
            Toast.makeText(this, "سرویس متوقف شد", Toast.LENGTH_SHORT).show()
        }
        btnClearHistory.setOnClickListener {
            PrefsManager.clearHistory(this)
            refreshLinkHistory()
        }
    }

    // ── Permission Handling ────────────────────────────────────────────────────

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "مجوز قبلاً فعال است", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilityLauncher.launch(intent)
        Toast.makeText(
            this,
            "«${getString(R.string.app_name)}» را پیدا کنید و فعال کنید",
            Toast.LENGTH_LONG
        ).show()
    }

    // ── UI Updates ─────────────────────────────────────────────────────────────

    private fun updatePermissionUI() {
        val overlayOk = hasOverlayPermission()
        val accessOk = hasAccessibilityPermission()

        tvOverlayStatus.text = if (overlayOk)
            getString(R.string.permission_overlay_ok)
        else
            getString(R.string.permission_overlay_no)
        tvOverlayStatus.setTextColor(
            getColor(if (overlayOk) R.color.green else R.color.red)
        )
        btnOverlay.isEnabled = !overlayOk

        tvAccessibilityStatus.text = if (accessOk)
            getString(R.string.permission_accessibility_ok)
        else
            getString(R.string.permission_accessibility_no)
        tvAccessibilityStatus.setTextColor(
            getColor(if (accessOk) R.color.green else R.color.red)
        )
        btnAccessibility.isEnabled = !accessOk
    }

    private fun updateSelectedAppsUI() {
        val selected = PrefsManager.getSelectedApps(this)
        tvSelectedApps.text = if (selected.isEmpty())
            getString(R.string.no_apps_selected)
        else
            getString(R.string.selected_apps_count, selected.size)
    }

    private fun updateServiceStatus(active: Boolean) {
        tvServiceStatus.text = if (active)
            getString(R.string.status_active)
        else
            getString(R.string.status_inactive)
        tvServiceStatus.setTextColor(
            getColor(if (active) R.color.green else android.R.color.holo_blue_light)
        )
    }

    private fun refreshLinkHistory() {
        val links = PrefsManager.getLinkHistory(this)
        linkAdapter.updateLinks(links)
        tvEmptyLinks.visibility = if (links.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        rvLinks.visibility = if (links.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ── Service Control ────────────────────────────────────────────────────────

    private fun startFloatingService() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "ابتدا مجوز نمایش روی برنامه‌ها را فعال کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAccessibilityPermission()) {
            Toast.makeText(this, "ابتدا سرویس دسترس‌پذیری را فعال کنید", Toast.LENGTH_LONG).show()
            return
        }
        FloatingWindowService.start(this)
        updateServiceStatus(active = true)
        Toast.makeText(this, "سرویس شناور شروع شد! برنامه را ببندید و وارد برنامه هدف شوید.", Toast.LENGTH_LONG).show()
    }
}
