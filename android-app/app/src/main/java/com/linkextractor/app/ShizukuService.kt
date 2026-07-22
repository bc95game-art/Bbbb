package com.linkextractor.app

/**
 * سرویس ممتاز Shizuku — با سطح دسترسی shell اجرا می‌شود.
 * این کلاس در فرآیند shell اجرا می‌شود، نه در فرآیند برنامه.
 */
class ShizukuService : IShizukuService.Stub() {

    override fun runCommand(command: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
