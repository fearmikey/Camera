package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

/**
 * Streams a YUV_420_888 burst into a native accumulator one frame at a time, so
 * only a single camera buffer needs to be held. Each frame is aligned to the
 * first (median-threshold-bitmap translation) and averaged; the result is tone
 * mapped to lift shadows. Not thread-safe: feed frames from one thread.
 */
class NightStacker(val width: Int, val height: Int) {

    private var ptr: Long = NativeVisionBridge.nativeNightCreate(width, height)

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
     * Produces the merged, upright image. [toneMap] lifts shadows (night); disable it
     * for long-exposure motion blur. Returns null if no frame was accepted.
     */
    fun finish(rotationDegrees: Int, toneMap: Boolean = true): Bitmap? {
        if (ptr == 0L || frameCount == 0) return null
        val swap = rotationDegrees % 180 != 0
        val out = Bitmap.createBitmap(
            if (swap) height else width,
            if (swap) width else height,
            Bitmap.Config.ARGB_8888
        )
        return if (NativeVisionBridge.nativeNightFinish(ptr, out, rotationDegrees, toneMap)) {
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
