package com.linkextractor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * جایگزین AccessibilityService — از MediaProjection برای ضبط صفحه
 * و ML Kit برای خواندن متن استفاده می‌کند.
 *
 * مزایا نسبت به Accessibility:
 *  - نیازی به مجوز دسترس‌پذیری ندارد
 *  - یک بار مجوز MediaProjection (یک تپ ساده) — همیشه کار می‌کند
 *  - روی همه دستگاه‌ها حتی MIUI کار می‌کند
 */
class ScreenLinkExtractor(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {

    companion object {
        // همان Regex که در LinkAccessibilityService استفاده می‌شود
        private val URL_REGEX = Regex(
            "(https?://[^\\s\"'<>\\[\\]{}|\\\\^`]+|[a-zA-Z][a-zA-Z0-9+.\\-]{2,}://[^\\s\"'<>\\[\\]{}|\\\\^`]+)",
            RegexOption.IGNORE_CASE
        )
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
        val wm = context.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        val width  = dm.widthPixels
        val height = dm.heightPixels

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = try {
            mediaProjection.createVirtualDisplay(
                "LinkCapture",
                width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
        } catch (e: Exception) {
            imageReader.close()
            return null
        }

        // یک فریم کامل صبر می‌کنیم
        Thread.sleep(250)

        val image = imageReader.acquireLatestImage()
        val bitmap: Bitmap? = image?.use { img ->
            val planes    = img.planes
            val buffer    = planes[0].buffer
            val rowStride = planes[0].rowStride
            val pixStride = planes[0].pixelStride
            val rowPad    = rowStride - pixStride * width

            val bmp = Bitmap.createBitmap(
                width + rowPad / pixStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            // برش تا اندازه واقعی صفحه (بدون padding)
            Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
        }

        virtualDisplay.release()
        imageReader.close()
        return bitmap
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
