package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI bridge to the OpenCV-based computational photography primitives in
 * `app/src/main/cpp/vision_jni.cpp`. All functions are thread-safe as long as a
 * given night-stacker pointer is only used from one thread at a time.
 *
 * Bitmaps passed to native code must be [Bitmap.Config.ARGB_8888].
 */
object NativeVisionBridge {
    init {
        System.loadLibrary("panorama_engine")
    }

    // Night mode: aligned temporal averaging of a YUV_420_888 burst.
    external fun nativeNightCreate(width: Int, height: Int): Long
    external fun nativeNightAddFrame(
        ptr: Long,
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): Boolean
    external fun nativeNightFrameCount(ptr: Long): Int
    external fun nativeNightFinish(ptr: Long, out: Bitmap, rotationDegrees: Int, toneMap: Boolean): Boolean
    external fun nativeNightRelease(ptr: Long)

    // HDR: MTB alignment + Mertens exposure fusion. All inputs and output share one size.
    external fun nativeFuseExposures(inputs: Array<Bitmap>, out: Bitmap): Boolean

    // Portrait: face-seeded GrabCut + background bokeh, applied to [bitmap] in place.
    // Face coordinates are normalized (0..1); pass all zeros when no face was found.
    external fun nativePortraitBokeh(
        bitmap: Bitmap,
        faceLeft: Float,
        faceTop: Float,
        faceRight: Float,
        faceBottom: Float,
        strength: Float,
    ): Boolean

    // QR code payload in [bitmap], or null.
    external fun nativeDecodeQr(bitmap: Bitmap): String?

    // Open-palm detector. Returns normalized {left, top, right, bottom} or null.
    external fun nativeDetectOpenPalm(
        bitmap: Bitmap,
        faceLeft: Float,
        faceTop: Float,
        faceRight: Float,
        faceBottom: Float,
    ): FloatArray?
}
