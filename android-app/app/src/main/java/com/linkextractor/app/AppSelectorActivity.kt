package com.linkextractor.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.RecyclerView

class AppSelectorActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selector)

        supportActionBar?.apply {
            title = getString(R.string.select_apps)
            setDisplayHomeAsUpEnabled(true)
        }

        rvApps = findViewById(R.id.rvApps)
        etSearch = findViewById(R.id.etSearch)
        btnSave = findViewById(R.id.btnSave)

        loadApps()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSave.setOnClickListener {
            PrefsManager.setSelectedApps(this, adapter.getSelectedPackages())
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadApps() {
        val pm = packageManager
        val selectedApps = PrefsManager.getSelectedApps(this)

        // Get all installed apps (excluding system apps for cleaner list)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { appInfo ->
                try {
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo.packageName),
                        isSelected = appInfo.packageName in selectedApps
                    )
                } catch (_: Exception) { null }
            }
            .sortedWith(
                compareByDescending<AppInfo> { it.isSelected }
                    .thenBy { it.appName }
            )
            .toMutableList()

        adapter = AppAdapter(apps)
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
    }
}
