package com.linkextractor.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.linkextractor.app.databinding.ActivityAppSelectorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectorBinding
    private lateinit var adapter: AppAdapter
    private var loadJob: Job? = null   // cancelled in onDestroy to prevent leaks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.select_apps)
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        setupSearch()
        setupSaveButton()
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── RecyclerView ───────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = AppAdapter(mutableListOf())
        binding.rvApps.apply {
            layoutManager = LinearLayoutManager(this@AppSelectorActivity)
            adapter = this@AppSelectorActivity.adapter
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
                val count = adapter.itemCount
                binding.tvResultCount.text = if (count > 0) "$count برنامه" else ""
                binding.tvEmptyApps.isVisible = count == 0 && binding.progressBar.isVisible.not()
            }
        })
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val selected = adapter.getSelectedPackages()
            PrefsManager.setSelectedApps(this, selected)
            val msg = if (selected.isEmpty()) "همه برنامه‌ها پایش می‌شوند"
                      else "${selected.size} برنامه انتخاب شد"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    // ── Load Apps (async with coroutines) ──────────────────────────────────────

    private fun loadApps() {
        binding.progressBar.isVisible = true
        binding.rvApps.isVisible      = false
        binding.tvEmptyApps.isVisible = false

        loadJob = CoroutineScope(Dispatchers.IO).launch {
            val selectedApps = PrefsManager.getSelectedApps(this@AppSelectorActivity)
            val pm = packageManager

            val apps = try {
                getInstalledUserApps(pm)
                    .mapNotNull { appInfo ->
                        runCatching {
                            AppInfo(
                                packageName = appInfo.packageName,
                                appName     = pm.getApplicationLabel(appInfo).toString(),
                                icon        = pm.getApplicationIcon(appInfo.packageName),
                                isSelected  = appInfo.packageName in selectedApps
                            )
                        }.getOrNull()
                    }
                    .sortedWith(
                        compareByDescending<AppInfo> { it.isSelected }.thenBy { it.appName }
                    )
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext

                binding.progressBar.isVisible = false

                if (apps.isEmpty()) {
                    binding.tvEmptyApps.isVisible = true
                    binding.tvEmptyApps.text      = "برنامه‌ای یافت نشد"
                } else {
                    binding.rvApps.isVisible = true
                    adapter = AppAdapter(apps.toMutableList())
                    binding.rvApps.adapter  = adapter
                    binding.tvResultCount.text = "${apps.size} برنامه"
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getInstalledUserApps(pm: PackageManager): List<ApplicationInfo> {
        val all: List<ApplicationInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.GET_META_DATA.toLong()
                    )
                )
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

        // Keep user-installed apps and updated system apps; skip pure system apps
        return all.filter { info ->
            val isSystem  = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem || isUpdated
        }
    }
}
