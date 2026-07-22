package com.linkextractor.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * کمک‌کننده Shizuku — دسترسی shell ممتاز بدون روت.
 */
object ShizukuHelper {

    const val REQUEST_CODE = 1001

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
     * دستور shell را با دسترسی ممتاز اجرا می‌کند.
     */
    fun runCommand(command: String): Boolean = runCatching {
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", command), null, null
        )
        val exit = process.waitFor()
        process.destroy()
        exit == 0
    }.getOrDefault(false)

    /**
     * دسترس‌پذیری برنامه را از طریق Shizuku فعال می‌کند.
     */
    fun enableAccessibilityService(packageName: String): Boolean {
        val component = "$packageName/com.linkextractor.app.LinkAccessibilityService"
        return runCommand(
            "settings put secure enabled_accessibility_services $component"
        )
    }
}
