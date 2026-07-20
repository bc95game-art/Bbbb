package com.linkextractor.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private val apps: MutableList<AppInfo>) :
    RecyclerView.Adapter<AppAdapter.AppVH>() {

    private var filteredApps: MutableList<AppInfo> = apps.toMutableList()

    class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPkg: TextView = view.findViewById(R.id.tvPackageName)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppVH(view)
    }

    override fun onBindViewHolder(holder: AppVH, position: Int) {
        val app = filteredApps[position]
        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvName.text = app.appName
        holder.tvPkg.text = app.packageName
        holder.cbSelected.isChecked = app.isSelected

        holder.itemView.setOnClickListener {
            app.isSelected = !app.isSelected
            holder.cbSelected.isChecked = app.isSelected
            // also update the original list
            apps.find { it.packageName == app.packageName }?.isSelected = app.isSelected
        }
    }

    override fun getItemCount() = filteredApps.size

    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            apps.toMutableList()
        } else {
            apps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): Set<String> =
        apps.filter { it.isSelected }.map { it.packageName }.toSet()
}
