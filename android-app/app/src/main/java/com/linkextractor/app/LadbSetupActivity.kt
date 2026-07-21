package com.linkextractor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.linkextractor.app.databinding.ActivityLadbSetupBinding

/**
 * راهنمای گام‌به‌گام نصب LADB و فعال‌سازی سرویس دسترس‌پذیری روی Xiaomi/MIUI
 */
class LadbSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLadbSetupBinding

    companion object {
        const val MODE_ACCESSIBILITY = "accessibility"
        const val MODE_OVERLAY       = "overlay"
        const val EXTRA_MODE         = "mode"
    }

    private val mode by lazy {
        intent.getStringExtra(EXTRA_MODE) ?: MODE_ACCESSIBILITY
    }

    private val adbCommand by lazy {
        if (mode == MODE_OVERLAY) {
            "appops set $packageName SYSTEM_ALERT_WINDOW allow"
        } else {
            "settings put secure enabled_accessibility_services " +
            "$packageName/com.linkextractor.app.LinkAccessibilityService"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLadbSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = if (mode == MODE_OVERLAY) "فعال‌سازی نمایش شناور" else "فعال‌سازی دسترس‌پذیری"
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val steps = buildSteps()
        binding.rvSteps.layoutManager = LinearLayoutManager(this)
        binding.rvSteps.adapter = StepAdapter(steps)

        binding.tvCommand.text = adbCommand

        binding.btnCopyCommand.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
            Toast.makeText(this, "✓ دستور کپی شد — در LADB Paste کنید", Toast.LENGTH_LONG).show()
        }

        binding.btnInstallLabd.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.draco.ladb"))
                )
            }
        }

        binding.btnDone.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildSteps(): List<Step> = listOf(
        Step(
            number  = "۱",
            title   = "حالت توسعه‌دهنده را فعال کن",
            detail  = "تنظیمات → درباره گوشی → ۷ بار روی «نسخه MIUI» بزن تا پیام «توسعه‌دهنده شدید» ظاهر شود.",
            icon    = "⚙"
        ),
        Step(
            number  = "۲",
            title   = "اشکال‌زدایی بی‌سیم را روشن کن",
            detail  = "تنظیمات → گزینه‌های توسعه‌دهنده → «اشکال‌زدایی بی‌سیم» را روشن کن.\nروی آن ضربه بزن → «Pair device with pairing code» را انتخاب کن. صفحه را باز نگه دار.",
            icon    = "📶"
        ),
        Step(
            number  = "۳",
            title   = "LADB را نصب کن",
            detail  = "روی دکمه «نصب LADB» پایین صفحه بزن تا به پلی‌استور برود.\nLADB یک ابزار رسمی برای اجرای دستورات ADB بدون کامپیوتر است.",
            icon    = "📲"
        ),
        Step(
            number  = "۴",
            title   = "LADB را با گوشی Pair کن",
            detail  = "LADB را باز کن → «Pair» را بزن → کد ۶ رقمی و شماره پورت را از صفحه «Pair device» که در مرحله ۲ باز است وارد کن.",
            icon    = "🔗"
        ),
        Step(
            number  = "۵",
            title   = "دستور را کپی و اجرا کن",
            detail  = "روی دکمه «کپی دستور» پایین این صفحه بزن.\nبه LADB برگرد، انگشتت را روی کادر ورودی نگه دار و «Paste» را بزن.\nسپس Enter کن — تمام!",
            icon    = "▶"
        ),
        Step(
            number  = "۶",
            title   = "برگرد و «فعال‌سازی» را دوباره بزن",
            detail  = "به برنامه «استخراج لینک» برگرد و دکمه فعال‌سازی را بزن.\nاگر همه چیز درست باشد، وضعیت سبز می‌شود.",
            icon    = "✅"
        )
    )

    // ── Step model ─────────────────────────────────────────────────────────────

    data class Step(
        val number: String,
        val title:  String,
        val detail: String,
        val icon:   String
    )

    // ── RecyclerView Adapter ───────────────────────────────────────────────────

    inner class StepAdapter(private val steps: List<Step>) :
        RecyclerView.Adapter<StepAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card:   CardView = view.findViewById(R.id.cardStep)
            val tvNum:  TextView = view.findViewById(R.id.tvStepNumber)
            val tvIcon: TextView = view.findViewById(R.id.tvStepIcon)
            val tvTitle:TextView = view.findViewById(R.id.tvStepTitle)
            val tvDetail:TextView= view.findViewById(R.id.tvStepDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_step, parent, false))

        override fun getItemCount() = steps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val step = steps[position]
            holder.tvNum.text    = step.number
            holder.tvIcon.text   = step.icon
            holder.tvTitle.text  = step.title
            holder.tvDetail.text = step.detail
        }
    }
}
