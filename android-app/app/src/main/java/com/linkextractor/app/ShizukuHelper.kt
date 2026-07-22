package com.linkextractor.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * کمک‌کننده Shizuku — دسترسی shell ممتاز بدون روت.
 *
 * جریان کار:
 * 1. بررسی نصب بودن Shizuku
 * 2. بررسی در حال اجرا بودن Shizuku (نسخه ≥ 11)
 * 3. درخواست مجوز از کاربر (یک بار)
 * 4. اجرای دستور shell با دسترسی کامل
 */
object ShizukuHelper {

    private const val REQUEST_CODE = 1001

    /** آیا Shizuku نصب است؟ */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** آیا Shizuku در حال اجرا و نسخه کافی دارد؟ */
    fun isRunning(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    }.getOrDefault(false)

    /** آیا برنامه مجوز Shizuku را دارد؟ */
    fun hasPermission(): Boolean = runCatching {
        if (Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false)

    /** درخواست مجوز از کاربر */
    fun requestPermission() {
        runCatching {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    /**
     * دستور shell را با دسترسی ممتاز اجرا می‌کند.
     * @return true اگر موفق، false اگر خطا
     */
    fun runCommand(command: String): Boolean = runCatching {
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", command),
            null,
            null
        )
        val exitCode = process.waitFor()
        process.destroy()
        exitCode == 0
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
