package com.linkextractor.app

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * سرویس ممتاز Shizuku — با سطح دسترسی shell اجرا می‌شود.
 * این کلاس در فرآیند shell اجرا می‌شود، نه در فرآیند برنامه.
 */
class ShizukuService : IShizukuService.Stub() {

    /** دستور را اجرا می‌کند و exit code برمی‌گرداند. */
    override fun runCommand(command: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    /** دستور را اجرا می‌کند و stdout را برمی‌گرداند. */
    override fun runCommandWithOutput(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream))
                .readText()
                .trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
