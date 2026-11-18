package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.FaceDetector

/**
 * Face localisation using the Android framework's built-in [FaceDetector]
 * (part of AOSP, no model files or third-party libraries required).
 *
 * Returned rectangles are normalized to 0..1 relative to the input bitmap.
 */
object FaceLocator {

    private const val MAX_FACES = 3
    private const val MIN_CONFIDENCE = 0.3f
    private const val DETECT_MAX_SIDE = 480

    /**
     * Finds the largest face in [source]. [source] may be any config; it is
     * downscaled internally to keep detection fast.
     */
    fun findLargestFace(source: Bitmap): RectF? {
        val scale = DETECT_MAX_SIDE.toFloat() / maxOf(source.width, source.height)
        var w = (source.width * scale.coerceAtMost(1f)).toInt()
        val h = (source.height * scale.coerceAtMost(1f)).toInt()
        if (w % 2 != 0) w -= 1 // FaceDetector requires an even width
        if (w < 16 || h < 16) return null

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
        if (scaled !== source) scaled.recycle()

        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(w, h, MAX_FACES).findFaces(rgb565, faces)
            val mid = PointF()
            faces.take(count)
                .filterNotNull()
                .filter { it.confidence() >= MIN_CONFIDENCE }
                .maxByOrNull { it.eyesDistance() }
                ?.let { face ->
                    face.getMidPoint(mid)
                    val eye = face.eyesDistance()
                    // Expand from the eye midpoint to an approximate head box.
                    RectF(
                        ((mid.x - 1.2f * eye) / w).coerceIn(0f, 1f),
                        ((mid.y - 1.3f * eye) / h).coerceIn(0f, 1f),
                        ((mid.x + 1.2f * eye) / w).coerceIn(0f, 1f),
                        ((mid.y + 1.9f * eye) / h).coerceIn(0f, 1f),
                    )
                }
        } catch (e: Exception) {
            android.util.Log.w("FaceLocator", "Face detection failed: ${e.message}")
            null
        } finally {
            rgb565.recycle()
        }
    }
}
