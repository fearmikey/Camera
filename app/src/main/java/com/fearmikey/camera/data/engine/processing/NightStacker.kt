package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

/**
 * Streams a YUV_420_888 burst into a native accumulator one frame at a time, so
 * only a single camera buffer needs to be held. Each frame is aligned to the
 * first (median-threshold-bitmap translation, refined to sub-pixel as the median over
 * up to three textured patches) and accumulated. Not thread-safe: feed frames from one
 * thread.
 *
 * Night ([longExposure] = false): 12-bit sum, brightened to a night-mode look (bright, with
 * contrast and true blacks) in linear light.
 * Long exposure: linear-light average (what one continuous exposure records) with
 * light-trail recovery for clipped moving highlights. Both finish at 16 bits, sharpen once
 * and dither to 8 bits (see [finish]).
 */
class NightStacker(val width: Int, val height: Int, val longExposure: Boolean = false) {

    private var ptr: Long = NativeVisionBridge.nativeNightCreate(width, height, longExposure)

    val frameCount: Int
        get() = if (ptr != 0L) NativeVisionBridge.nativeNightFrameCount(ptr) else 0

    /** Adds [image] to the stack and closes it. Returns false if the frame was rejected. */
    fun addFrame(image: Image): Boolean {
        image.use {
            if (ptr == 0L || it.format != ImageFormat.YUV_420_888) return false
            if (it.width != width || it.height != height) return false
            val y = it.planes[0]
            val u = it.planes[1]
            val v = it.planes[2]
            return NativeVisionBridge.nativeNightAddFrame(
                ptr, y.buffer, u.buffer, v.buffer, y.rowStride, u.rowStride, u.pixelStride
            )
        }
    }

    /**
     * Produces the merged, upright image. [minGain] is exposure the sensor settings could not
     * reach, applied in linear light with a highlight roll-off. Night additionally brightens
     * dark scenes automatically; Long exposure keeps the metered brightness. Returns null if no
     * frame was accepted.
     */
    fun finish(rotationDegrees: Int, minGain: Float = 1f): Bitmap? {
        if (ptr == 0L || frameCount == 0) return null
        val swap = rotationDegrees % 180 != 0
        val out = Bitmap.createBitmap(
            if (swap) height else width,
            if (swap) width else height,
            Bitmap.Config.ARGB_8888
        )
        return if (NativeVisionBridge.nativeNightFinish(ptr, out, rotationDegrees, minGain)) {
            out
        } else {
            out.recycle()
            null
        }
    }

    fun release() {
        if (ptr != 0L) {
            NativeVisionBridge.nativeNightRelease(ptr)
            ptr = 0L
        }
    }
}
