package com.linkextractor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class LinkAdapter(private val links: MutableList<String>) :
    RecyclerView.Adapter<LinkAdapter.LinkVH>() {

    class LinkVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLink: TextView = view.findViewById(R.id.tvLink)
        val btnCopy: ImageView = view.findViewById(R.id.btnCopyLink)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_link, parent, false)
        return LinkVH(view)
    }

    override fun onBindViewHolder(holder: LinkVH, position: Int) {
        val link = links[position]
        holder.tvLink.text = link
        holder.btnCopy.setOnClickListener {
            copyToClipboard(holder.itemView.context, link)
        }
        holder.itemView.setOnClickListener {
            copyToClipboard(holder.itemView.context, link)
        }
    }

    override fun getItemCount() = links.size

    fun updateLinks(newLinks: List<String>) {
        links.clear()
        links.addAll(newLinks)
        notifyDataSetChanged()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(context, "لینک کپی شد", Toast.LENGTH_SHORT).show()
    }
}
