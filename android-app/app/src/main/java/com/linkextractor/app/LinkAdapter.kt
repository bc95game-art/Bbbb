package com.linkextractor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

class LinkAdapter(private val links: MutableList<String>) :
    RecyclerView.Adapter<LinkAdapter.LinkVH>() {

    var onLinkCopied: ((String) -> Unit)? = null

    class LinkVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLink: TextView    = view.findViewById(R.id.tvLink)
        val tvIndex: TextView   = view.findViewById(R.id.tvLinkIndex)
        val btnCopy: ImageView  = view.findViewById(R.id.btnCopyLink)
        val btnOpen: ImageView  = view.findViewById(R.id.btnOpenLink)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_link, parent, false)
        return LinkVH(view)
    }

    override fun onBindViewHolder(holder: LinkVH, position: Int) {
        val link = links[position]
        holder.tvLink.text  = link
        holder.tvIndex.text = "${position + 1}"

        // Copy to clipboard
        holder.btnCopy.setOnClickListener {
            copyToClipboard(holder.itemView.context, link, holder.itemView)
            onLinkCopied?.invoke(link)
        }

        // Open in browser
        holder.btnOpen.setOnClickListener {
            openInBrowser(holder.itemView.context, link)
        }

        // Long-press → share
        holder.itemView.setOnLongClickListener {
            shareLink(holder.itemView.context, link)
            true
        }

        // Short tap → copy
        holder.itemView.setOnClickListener {
            copyToClipboard(holder.itemView.context, link, holder.itemView)
            onLinkCopied?.invoke(link)
        }
    }

    override fun getItemCount() = links.size

    /** Full replacement with DiffUtil animation. */
    fun updateLinks(newLinks: List<String>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = links.size
            override fun getNewListSize() = newLinks.size
            override fun areItemsTheSame(o: Int, n: Int) = links[o] == newLinks[n]
            override fun areContentsTheSame(o: Int, n: Int) = links[o] == newLinks[n]
        })
        links.clear()
        links.addAll(newLinks)
        diff.dispatchUpdatesTo(this)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun copyToClipboard(context: Context, text: String, anchor: View) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        Snackbar.make(anchor, "✓ لینک کپی شد", Snackbar.LENGTH_SHORT).show()
    }

    private fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            // URL might not be a valid http link (deep-link with no handler)
        }
    }

    private fun shareLink(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری لینک"))
    }
}
