package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI surface of the native sweep-panorama engine (panorama_jni.cpp / PanoramaEngine.cpp).
 * Use through [PanoramaSession], which owns the native pointer.
 */
internal object NativePanoramaBridge {
    const val STATUS_TRACKING = 0
    const val STATUS_KEYFRAME = 1
    const val STATUS_LOST = 2
    const val STATUS_TOO_FAST = 3
    const val STATUS_COMPLETE = 4

    /** [status, keyframes, sweepDegrees, verticalOffset, stripX, stripWidth, direction, keyframeDx, targetStep, aligned] */
    const val STATS_SIZE = 10

    init {
        System.loadLibrary("panorama_engine")
    }

    /** @param tanHalfFovLongSide tan(FOV / 2) along the analysis frame's long side. */
    external fun nativeCreate(tanHalfFovLongSide: Float): Long
    external fun nativeProcessFrame(
        enginePtr: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        rotationDegrees: Int,
        timestampNs: Long,
        stats: FloatArray,
    ): Int
    external fun nativeGetPreviewSize(enginePtr: Long): IntArray
    external fun nativeRenderPreview(enginePtr: Long, bitmap: Bitmap): Boolean
    external fun nativeStitch(enginePtr: Long): IntArray
    external fun nativeCopyResult(enginePtr: Long, bitmap: Bitmap): Boolean
    external fun nativeRelease(enginePtr: Long)

    /** Upright downscaled copy of an RGBA frame into [bitmap] (no engine needed). */
    external fun nativeThumbnail(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        rotationDegrees: Int,
        bitmap: Bitmap,
    ): Boolean
}
