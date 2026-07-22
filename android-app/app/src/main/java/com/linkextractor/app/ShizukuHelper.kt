package com.linkextractor.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * کمک‌کننده Shizuku — دسترسی shell ممتاز بدون روت.
 * از UserService استفاده می‌کند (روش صحیح در Shizuku v13+).
 */
object ShizukuHelper {

    const val REQUEST_CODE = 1001

    private var iService: IShizukuService? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                "com.linkextractor.app",
                ShizukuService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("shizuku_svc")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
            iService = IShizukuService.Stub.asInterface(binder)
            pendingCallback?.invoke(true)
            pendingCallback = null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            iService = null
        }
    }

    /** آیا Shizuku نصب است؟ */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** آیا Shizuku سرویس در حال اجرا است؟ */
    fun isRunning(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /** آیا برنامه مجوز Shizuku را دارد؟ */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** درخواست مجوز از کاربر */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /**
     * UserService را راه‌اندازی کرده و وقتی آماده شد callback را صدا می‌زند.
     */
    fun bindAndRun(callback: (Boolean) -> Unit) {
        if (iService != null) {
            callback(true)
            return
        }
        pendingCallback = callback
        runCatching {
            Shizuku.bindUserService(serviceArgs, connection)
        }.onFailure {
            pendingCallback = null
            callback(false)
        }
    }

    fun unbindService() {
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        iService = null
    }

    /**
     * دسترس‌پذیری برنامه را از طریق Shizuku فعال می‌کند.
     *
     * اصلاحات نسبت به نسخه قبل:
     *  1. لیست سرویس‌های فعلی را می‌خواند و سرویس جدید را APPEND می‌کند
     *     (به جای جایگزین کردن همه سرویس‌ها که باعث مسدود شدن می‌شد)
     *  2. accessibility_enabled را روی ۱ تنظیم می‌کند
     *
     * @return true اگر هر دو مرحله موفق باشند
     */
    fun enableAccessibilityService(packageName: String): Boolean = runCatching {
        val svc = iService ?: return@runCatching false
        val component = "$packageName/com.linkextractor.app.LinkAccessibilityService"

        // ① لیست فعلی را بخوان
        val current = svc.runCommandWithOutput(
            "settings get secure enabled_accessibility_services"
        ).trim()

        // ② اگر سرویس قبلاً وجود دارد نیازی به تغییر نیست
        if (component in current) {
            // فقط accessibility_enabled را مطمئن شو
            svc.runCommand("settings put secure accessibility_enabled 1")
            return@runCatching true
        }

        // ③ لیست جدید را بساز — append کن نه replace
        val newList = when {
            current.isBlank() || current == "null" -> component
            else -> "$current:$component"
        }

        // ④ هر دو دستور را اجرا کن
        val r1 = svc.runCommand(
            "settings put secure enabled_accessibility_services $newList"
        ) == 0
        val r2 = svc.runCommand(
            "settings put secure accessibility_enabled 1"
        ) == 0

        r1 && r2
    }.getOrDefault(false)
}
