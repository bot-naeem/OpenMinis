package com.openminis.app.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashSet

/**
 * ScreenContextCapturer extracts visual screenshot and structured text context
 * from the active foreground window using MinisAccessibilityService.
 */
object ScreenContextCapturer {

    private const val TAG = "ScreenCapturer"
    private const val MAX_IMAGE_EDGE = 1600
    private const val JPEG_QUALITY = 82
    private const val MAX_TEXT_CHARS = 2500

    data class ScreenContext(
        val screenshotFile: File? = null,
        val screenshotUri: Uri? = null,
        val packageName: String? = null,
        val appName: String? = null,
        val extractedText: String? = null,
        val isA11yActive: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun isAccessibilityActive(): Boolean = MinisAccessibilityService.getInstance() != null

    /**
     * Captures current screen screenshot and reads visible accessibility text.
     * Blocks for a short period while the screenshot callback finishes.
     */
    fun captureCurrentScreen(context: Context): ScreenContext {
        val svc = MinisAccessibilityService.getInstance()
        if (svc == null) {
            AppLogger.info(TAG, "AccessibilityService not connected; returning empty screen context")
            return ScreenContext(isA11yActive = false)
        }

        var shotFile: File? = null
        var shotUri: Uri? = null

        // 1. Capture screenshot (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val shotResult = svc.captureScreenshot(timeoutMs = 4000)
                val raw = shotResult.bitmap
                if (raw != null) {
                    shotFile = saveCompressedScreenshot(context, raw)
                    raw.recycle()
                    if (shotFile != null && shotFile.exists()) {
                        val authority = "${context.packageName}.fileprovider"
                        shotUri = FileProvider.getUriForFile(context, authority, shotFile)
                    }
                } else {
                    AppLogger.warning(TAG, "Screenshot bitmap is null: ${shotResult.errorMessage}")
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "captureScreenshot threw: ${t.message}")
            }
        }

        // 2. Foreground package & app name
        val (pkg, _) = svc.foregroundPackage()
        val appName = try {
            pkg?.let {
                val appInfo = context.packageManager.getApplicationInfo(it, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            }
        } catch (_: Throwable) {
            pkg
        }

        // 3. Extract visible UI texts from accessibility tree
        val text = try {
            extractVisibleText(svc.rootNodes(), MAX_TEXT_CHARS)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "extractVisibleText failed: ${t.message}")
            null
        }

        return ScreenContext(
            screenshotFile = shotFile,
            screenshotUri = shotUri,
            packageName = pkg,
            appName = appName,
            extractedText = text,
            isA11yActive = true,
        )
    }

    private fun saveCompressedScreenshot(context: Context, raw: Bitmap): File? {
        val dir = File(context.filesDir, "screen-assistant").also { it.mkdirs() }
        val file = File(dir, "screen_${System.currentTimeMillis()}.jpg")

        val origW = raw.width
        val origH = raw.height
        val maxEdge = maxOf(origW, origH)

        val targetBmp: Bitmap = if (maxEdge > MAX_IMAGE_EDGE) {
            val ratio = MAX_IMAGE_EDGE.toFloat() / maxEdge.toFloat()
            val newW = (origW * ratio).toInt().coerceAtLeast(1)
            val newH = (origH * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, newW, newH, true)
        } else {
            raw
        }

        return try {
            FileOutputStream(file).use { out ->
                targetBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
            if (targetBmp !== raw) {
                targetBmp.recycle()
            }
            file
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "saveCompressedScreenshot failed: ${t.message}")
            if (targetBmp !== raw) {
                targetBmp.recycle()
            }
            null
        }
    }

    private fun extractVisibleText(roots: List<AccessibilityNodeInfo>, maxChars: Int): String? {
        val lines = LinkedHashSet<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            try {
                if (node.isVisibleToUser) {
                    val t = node.text?.toString()?.trim()
                    if (!t.isNullOrBlank() && t.length > 1) {
                        lines.add(t)
                    }
                    val d = node.contentDescription?.toString()?.trim()
                    if (!d.isNullOrBlank() && d != t && d.length > 1) {
                        lines.add(d)
                    }
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            } catch (_: Throwable) {}
        }

        for (root in roots) {
            walk(root, 0)
        }

        if (lines.isEmpty()) return null
        val combined = lines.joinToString("\n")
        return if (combined.length > maxChars) {
            combined.take(maxChars) + "\n..."
        } else {
            combined
        }
    }
}
