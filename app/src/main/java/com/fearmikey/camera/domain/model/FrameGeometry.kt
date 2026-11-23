package com.fearmikey.camera.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Framing math shared by the engines so every photo matches what the viewfinder showed.
 * Pure Kotlin (no Android types) so FrameGeometryTest runs on the JVM.
 *
 * Aspects are long side / short side (always >= 1) unless a name says otherwise.
 */
object FrameGeometry {

    /** A crop rectangle in pixels. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Relative difference under which two aspects count as equal (no crop, "exact" size). */
    const val ASPECT_TOLERANCE = 0.01f

    fun longOverShort(ratio: Float): Float = if (ratio >= 1f) ratio else 1f / ratio

    fun longOverShort(width: Int, height: Int): Float =
        max(width, height).toFloat() / min(width, height).coerceAtLeast(1)

    fun sameAspect(a: Float, b: Float): Boolean = abs(a - b) / b < ASPECT_TOLERANCE

    /**
     * What the viewfinder shows for [ratio], as long/short: 4:3 and 16:9 viewfinders keep their
     * ratio; the full-screen one shows a center crop of the 4:3 stream at the viewfinder's own
     * shape ([viewfinderWOverH], null until the UI has measured it: no crop then).
     */
    fun photoAspect(ratio: AspectRatio, viewfinderWOverH: Float?): Float? = when (ratio) {
        AspectRatio.RATIO_4_3 -> 4f / 3f
        AspectRatio.RATIO_16_9 -> 16f / 9f
        AspectRatio.RATIO_FULL -> viewfinderWOverH?.takeIf { it > 0f }?.let { longOverShort(it) }
    }

    /** Aspect of the CameraX preview stream: 16:9 for the 16:9 setting, otherwise 4:3. */
    fun previewStreamAspect(ratio: AspectRatio): Float =
        if (ratio == AspectRatio.RATIO_16_9) 16f / 9f else 4f / 3f

    /**
     * Centered crop of a [width] x [height] image to [aspect] (long/short), keeping the image's
     * orientation (the long side stays the long side, as the viewfinder's long side is the
     * sensor's long side). The full frame when it already has that shape.
     */
    fun centerCrop(width: Int, height: Int, aspect: Float): Box {
        val long = max(width, height)
        val short = min(width, height)
        val current = long.toFloat() / short
        if (sameAspect(current, aspect)) return Box(0, 0, width, height)
        var newLong = long
        var newShort = short
        if (current > aspect) {
            newLong = (short * aspect).roundToInt().coerceIn(1, long)
        } else {
            newShort = (long / aspect).roundToInt().coerceIn(1, short)
        }
        val (w, h) = if (width >= height) newLong to newShort else newShort to newLong
        return Box((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * Picks an output size (width, height) for a still pipeline.
     *  - Only sizes up to [maxPixels] (the smallest size if none fits).
     *  - No [aspect]: the largest.
     *  - [preferExact] (the camera's own JPEG is saved as is): the largest size of exactly
     *    [aspect] if the camera has one.
     *  - Otherwise the size that keeps the most pixels after cropping to [aspect].
     */
    fun pickSize(
        sizes: List<Pair<Int, Int>>,
        maxPixels: Long,
        aspect: Float?,
        preferExact: Boolean,
    ): Pair<Int, Int>? {
        if (sizes.isEmpty()) return null
        fun area(s: Pair<Int, Int>) = s.first.toLong() * s.second
        val fitting = sizes.filter { area(it) <= maxPixels }.ifEmpty { listOf(sizes.minBy(::area)) }
        if (aspect == null) return fitting.maxBy(::area)
        if (preferExact) {
            fitting.filter { sameAspect(longOverShort(it.first, it.second), aspect) }
                .maxByOrNull(::area)?.let { return it }
        }
        fun croppedArea(s: Pair<Int, Int>): Long {
            val box = centerCrop(s.first, s.second, aspect)
            return box.width.toLong() * box.height
        }
        // Equal crops: prefer the smaller source (less to crop away).
        return fitting.maxWith(compareBy<Pair<Int, Int>>(::croppedArea).thenByDescending(::area))
    }

    /**
     * Maps a tap at normalized ([x], [y]) on the viewfinder to normalized coordinates in the
     * sensor's active array (sensor orientation, unrotated), for Camera2 metering regions.
     *
     * The UI is portrait-locked (display rotation 0). The viewfinder ([viewfinderWOverH], null =
     * same shape as the stream) shows the preview stream ([streamAspect], long/short) upright
     * with PreviewView FILL_CENTER, mirrored for the front camera. The stream is a center crop
     * of the active array ([activeAspect], long/short).
     */
    fun viewfinderToSensor(
        x: Float,
        y: Float,
        viewfinderWOverH: Float?,
        streamAspect: Float,
        activeAspect: Float,
        sensorOrientation: Int,
        isFront: Boolean,
    ): Pair<Float, Float> {
        val rotation = ((sensorOrientation % 360) + 360) % 360
        // Upright stream shape (width / height): portrait when the sensor is mounted sideways.
        val contentWOverH = if (rotation == 90 || rotation == 270) 1f / streamAspect else streamAspect
        val vf = viewfinderWOverH?.takeIf { it > 0f } ?: contentWOverH
        // FILL_CENTER: the stream covers the viewfinder, cropped on one axis.
        var cx = x
        var cy = y
        if (contentWOverH > vf) {
            cx = 0.5f + (x - 0.5f) * (vf / contentWOverH)
        } else {
            cy = 0.5f + (y - 0.5f) * (contentWOverH / vf)
        }
        if (isFront) cx = 1f - cx
        // Undo the clockwise rotation that turned the sensor image upright.
        val (sx, sy) = when (rotation) {
            90 -> cy to 1f - cx
            180 -> 1f - cx to 1f - cy
            270 -> 1f - cy to cx
            else -> cx to cy
        }
        // The stream within the active array (both in sensor orientation, landscape).
        var ax = sx
        var ay = sy
        if (streamAspect > activeAspect) {
            ay = 0.5f + (sy - 0.5f) * (activeAspect / streamAspect)
        } else if (streamAspect < activeAspect) {
            ax = 0.5f + (sx - 0.5f) * (streamAspect / activeAspect)
        }
        return ax.coerceIn(0f, 1f) to ay.coerceIn(0f, 1f)
    }
}
