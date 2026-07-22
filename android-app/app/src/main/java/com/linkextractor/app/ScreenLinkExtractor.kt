package com.linkextractor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * استخراج لینک از صفحه نمایش با استفاده از Shizuku + ML Kit OCR.
 *
 * روش کار:
 *  1. Shizuku دستور screencap را اجرا کرده و تصویر را ذخیره می‌کند
 *  2. ML Kit متن را از تصویر می‌خواند
 *  3. لینک‌ها با Regex پیدا می‌شوند
 *
 * مزایا نسبت به MediaProjection:
 *  - هیچ crash روی Android 14+ نمی‌دهد (مشکل android.project_media وجود ندارد)
 *  - نیاز به هیچ دیالوگ مجوز اضافه‌ای نیست
 *  - روی MIUI/Xiaomi و سایر دستگاه‌ها بدون محدودیت کار می‌کند
 */
class ScreenLinkExtractor(private val context: Context) {

    companion object {
        private val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>\\[\\]{}|\\\\^`]+|[a-zA-Z][a-zA-Z0-9+.\\-]{2,}://[^\\s\"'<>\\[\\]{}|\\\\^`]+)",
            RegexOption.IGNORE_CASE
        )
        private const val CAPTURE_PATH = "/data/local/tmp/lnk_sc.png"
    }

    /**
     * صفحه را ضبط می‌کند، OCR اجرا می‌کند و لینک‌ها را برمی‌گرداند.
     * روی Dispatcher.IO اجرا می‌شود.
     */
    suspend fun extractLinks(): List<String> = withContext(Dispatchers.IO) {
        val bitmap = captureScreen() ?: return@withContext emptyList()
        val text = recognizeText(bitmap)
        bitmap.recycle()
        extractUrlsFromText(text)
    }

    // ── Screen Capture ─────────────────────────────────────────────────────────

    private fun captureScreen(): Bitmap? {
        // روش اول: screencap → فایل → BitmapFactory (سریع‌ترین روش)
        ShizukuHelper.runShellCommand("screencap -p $CAPTURE_PATH")
        Thread.sleep(400) // صبر برای تکمیل نوشتن فایل

        val file = File(CAPTURE_PATH)
        if (file.exists()) {
            return try {
                val bmp = BitmapFactory.decodeFile(CAPTURE_PATH)
                ShizukuHelper.runShellCommand("rm -f $CAPTURE_PATH")
                bmp
            } catch (e: Exception) {
                ShizukuHelper.runShellCommand("rm -f $CAPTURE_PATH")
                // اگر خواندن مستقیم ناموفق بود، به روش base64 برو
                captureViaBase64()
            }
        }

        // روش دوم: base64 encoding (برای دستگاه‌های با SELinux سخت‌گیر مثل MIUI)
        return captureViaBase64()
    }

    /**
     * fallback: screencap را به base64 تبدیل کرده و در app decode می‌کند.
     * این روش از محدودیت‌های SELinux عبور می‌کند.
     */
    private fun captureViaBase64(): Bitmap? {
        val b64 = ShizukuHelper.runShellCommand(
            "screencap -p $CAPTURE_PATH 2>/dev/null && base64 $CAPTURE_PATH; rm -f $CAPTURE_PATH"
        ).trim()
        if (b64.isBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // ── OCR (ML Kit) ───────────────────────────────────────────────────────────

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                    recognizer.close()
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                    recognizer.close()
                }
            cont.invokeOnCancellation { recognizer.close() }
        }

    // ── URL Extraction ─────────────────────────────────────────────────────────

    private fun extractUrlsFromText(text: String): List<String> {
        return URL_REGEX.findAll(text)
            .map { it.value.trimEnd('/', '.', ',', ')', ']', '}') }
            .filter { it.length > 10 }
            .distinct()
            .toList()
    }
}
