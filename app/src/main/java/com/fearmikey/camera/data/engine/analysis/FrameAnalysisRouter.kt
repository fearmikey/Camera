package com.fearmikey.camera.data.engine.analysis

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.fearmikey.camera.data.engine.gesture.GestureAnalyzer
import com.fearmikey.camera.data.engine.processing.NativeVisionBridge

/**
 * CameraX allows one analyzer per ImageAnalysis, so this fans each frame out to
 * the enabled sub-analyzers (palm timer, QR scanner) and closes it afterwards.
 */
class FrameAnalysisRouter(
    private val gesture: GestureAnalyzer?,
    private val qr: QrCodeAnalyzer?,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            qr?.processFrame(image)
            gesture?.processFrame(image)
        } finally {
            image.close()
        }
    }
}

/**
 * Decodes QR codes with OpenCV's QRCodeDetector (FOSS, on-device). Reports a
 * payload once when it appears and null once it has been out of view for a while.
 */
class QrCodeAnalyzer(private val onResult: (String?) -> Unit) {
    private var frameCounter = 0
    private var lastPayload: String? = null
    private var lastSeen = 0L

    fun processFrame(image: ImageProxy) {
        frameCounter++
        if (frameCounter % FRAME_SKIP != 0) return
        val now = System.currentTimeMillis()
        val bitmap = try {
            image.toUprightBitmap(MAX_SIDE)
        } catch (e: Exception) {
            return
        }
        val text = try {
            NativeVisionBridge.nativeDecodeQr(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "QR decode error: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
        if (!text.isNullOrBlank()) {
            lastSeen = now
            if (text != lastPayload) {
                lastPayload = text
                onResult(text)
            }
        } else if (lastPayload != null && now - lastSeen > CLEAR_AFTER_MS) {
            lastPayload = null
            onResult(null)
        }
    }

    private companion object {
        const val TAG = "QrCodeAnalyzer"
        const val FRAME_SKIP = 5
        const val MAX_SIDE = 1024
        const val CLEAR_AFTER_MS = 4000L
    }
}

/** Upright ARGB_8888 copy of the frame no larger than [maxSide]. */
fun ImageProxy.toUprightBitmap(maxSide: Int): Bitmap {
    val raw = toBitmap()
    val scale = (maxSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0 && scale == 1f && raw.config == Bitmap.Config.ARGB_8888) return raw
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (out !== raw) raw.recycle()
    return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle() }
}
