package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.fearmikey.camera.domain.model.PanoramaProgress

/**
 * Bound in Panorama mode. While a sweep is active it feeds every frame to the
 * [PanoramaSession] (synchronously, so KEEP_ONLY_LATEST back-pressure drops frames instead
 * of queueing them) and reports progress, throttled to ~15 Hz except for keyframes and hint
 * changes. Before a sweep it emits a small live thumbnail (~8 Hz) for the corner preview.
 */
class PanoramaFrameAnalyzer(
    private val session: () -> PanoramaSession?,
    private val onProgress: (PanoramaProgress) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastEmit = 0L
    private var lastProgress: PanoramaProgress? = null
    private val thumbs = arrayOfNulls<Bitmap>(2)
    private var nextThumb = 0

    override fun analyze(image: ImageProxy) {
        try {
            val active = session()
            if (active == null) {
                lastProgress = null
                emitIdleThumbnail(image)
                return
            }
            val progress = active.process(image) ?: return
            val now = SystemClock.elapsedRealtime()
            val previous = lastProgress
            val important = previous == null || progress.hint != previous.hint ||
                progress.keyframes != previous.keyframes || progress.aligned != previous.aligned
            if (important || now - lastEmit >= 66) {
                lastEmit = now
                lastProgress = progress
                onProgress(progress)
            }
        } finally {
            image.close()
        }
    }

    private fun emitIdleThumbnail(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit < 120) return
        val plane = image.planes[0]
        if (!plane.buffer.isDirect) return
        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val uprightW = if (swap) image.height else image.width
        val uprightH = if (swap) image.width else image.height
        val h = THUMB_HEIGHT
        val w = (h * uprightW / uprightH).coerceAtLeast(1)
        var bitmap = thumbs[nextThumb]
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            thumbs[nextThumb] = bitmap
        }
        val ok = NativePanoramaBridge.nativeThumbnail(
            plane.buffer, image.width, image.height, plane.rowStride, rotation, bitmap
        )
        if (!ok) return
        nextThumb = 1 - nextThumb
        lastEmit = now
        onProgress(PanoramaProgress(preview = bitmap, contentStart = 0f, contentEnd = 1f, isIdlePreview = true))
    }

    private companion object {
        const val THUMB_HEIGHT = 320
    }
}
