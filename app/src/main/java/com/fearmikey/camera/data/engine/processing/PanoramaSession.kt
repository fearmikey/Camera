package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.fearmikey.camera.domain.model.PanoramaHint
import com.fearmikey.camera.domain.model.PanoramaProgress
import kotlin.math.abs

/** The sweep was too short (fewer than two overlapping keyframes) to stitch. */
class PanoramaTooShortException : Exception("Pan further to capture a panorama")

/**
 * One panorama sweep backed by a native PanoramaEngine.
 *
 * Threading: [process] must only be called from the analysis executor; [stitch] and
 * [close] must only be called once no analyzer can reach this session any more
 * (CameraXProvider detaches it on the analysis executor first).
 */
class PanoramaSession(tanHalfFovLongSide: Float) : AutoCloseable {

    private var ptr: Long = NativePanoramaBridge.nativeCreate(tanHalfFovLongSide)
    private val stats = FloatArray(NativePanoramaBridge.STATS_SIZE)
    private var preview: Bitmap? = null
    // Two strip bitmaps used alternately: native draws into one while Compose shows the other.
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var nextBuffer = 0
    private var lastRender = 0L
    private var contentStart = 0f
    private var contentEnd = 0f

    /** Feeds one RGBA_8888 frame and returns the resulting progress. */
    fun process(image: ImageProxy): PanoramaProgress? {
        if (ptr == 0L) return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (!buffer.isDirect) return null
        val status = NativePanoramaBridge.nativeProcessFrame(
            ptr, buffer, image.width, image.height, plane.rowStride,
            image.imageInfo.rotationDegrees, image.imageInfo.timestamp, stats,
        )
        // The strip includes the live view, so refresh it at UI rate (and on every keyframe).
        val now = android.os.SystemClock.elapsedRealtime()
        if (status == NativePanoramaBridge.STATUS_KEYFRAME || preview == null || now - lastRender >= 66) {
            lastRender = now
            renderPreview()
        }
        return toProgress(status)
    }

    private fun renderPreview() {
        val size = NativePanoramaBridge.nativeGetPreviewSize(ptr)
        if (size[0] <= 0 || size[1] <= 0) return
        var bitmap = buffers[nextBuffer]
        if (bitmap == null || bitmap.width != size[0] || bitmap.height != size[1]) {
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
            buffers[nextBuffer] = bitmap
        }
        if (!NativePanoramaBridge.nativeRenderPreview(ptr, bitmap)) return
        nextBuffer = 1 - nextBuffer
        preview = bitmap
        // Horizontal extent of what has been captured (non-transparent columns, middle row).
        val row = IntArray(bitmap.width)
        bitmap.getPixels(row, 0, bitmap.width, 0, bitmap.height / 2, bitmap.width, 1)
        val first = row.indexOfFirst { it ushr 24 != 0 }
        val last = row.indexOfLast { it ushr 24 != 0 }
        if (first >= 0) {
            contentStart = first.toFloat() / bitmap.width
            contentEnd = (last + 1).toFloat() / bitmap.width
        }
    }

    private fun toProgress(status: Int): PanoramaProgress {
        val direction = stats[6].toInt()
        val vertical = stats[3]
        val hint = when (status) {
            NativePanoramaBridge.STATUS_COMPLETE -> PanoramaHint.COMPLETE
            NativePanoramaBridge.STATUS_LOST -> PanoramaHint.LOST
            NativePanoramaBridge.STATUS_TOO_FAST -> PanoramaHint.SLOW_DOWN
            else -> when {
                abs(vertical) > LEVEL_TOLERANCE -> if (vertical > 0) PanoramaHint.TILT_UP else PanoramaHint.TILT_DOWN
                direction == 0 -> PanoramaHint.START
                else -> PanoramaHint.KEEP_GOING
            }
        }
        return PanoramaProgress(
            hint = hint,
            keyframes = stats[1].toInt(),
            sweepDegrees = stats[2],
            verticalOffset = vertical,
            frameX = stats[4],
            frameWidth = stats[5],
            direction = direction,
            keyframeDx = stats[7],
            targetStep = stats[8],
            aligned = stats[9] > 0.5f,
            preview = preview,
            contentStart = contentStart,
            contentEnd = contentEnd,
        )
    }

    /** Stitches the sweep. Returns null when there was not enough overlap/motion. */
    fun stitch(): Bitmap? {
        if (ptr == 0L) return null
        val size = NativePanoramaBridge.nativeStitch(ptr)
        if (size[0] <= 0 || size[1] <= 0) return null
        val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        if (!NativePanoramaBridge.nativeCopyResult(ptr, bitmap)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    override fun close() {
        if (ptr != 0L) {
            NativePanoramaBridge.nativeRelease(ptr)
            ptr = 0L
        }
        preview = null
    }

    companion object {
        /** Vertical drift (fraction of frame height) before asking the user to re-level. */
        const val LEVEL_TOLERANCE = 0.08f
    }
}
