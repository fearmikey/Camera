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

    // Night / Long exposure: aligned temporal merge of a YUV_420_888 burst. [longExposure]
    // accumulates linear light + per-pixel peak instead of the night 8-bit sum.
    external fun nativeNightCreate(width: Int, height: Int, longExposure: Boolean): Long
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
    // Night: brighten in linear light by max(minGain, measured gain, up to 5 EV) with a black
    // point, highlight roll-off, contrast curve and chroma denoise. Long exposure: linear mean
    // x minGain with light-trail recovery. Both are sharpened once and dithered from 16 to 8 bits.
    external fun nativeNightFinish(ptr: Long, out: Bitmap, rotationDegrees: Int, minGain: Float): Boolean
    external fun nativeNightRelease(ptr: Long)

    // HDR: MTB alignment + Mertens exposure fusion. All inputs and output share one size.
    external fun nativeFuseExposures(inputs: Array<Bitmap>, out: Bitmap): Boolean

    // Portrait: face-seeded GrabCut + guided-filter matte + lens bokeh, applied to [bitmap]
    // in place (PortraitEngine.cpp). [faceBoxes] holds normalized (0..1) {left, top, right,
    // bottom} per face (empty = centered subject); [strength] 1.0 ~ f/2.8 (PortraitAperture).
    external fun nativePortraitBokeh(bitmap: Bitmap, faceBoxes: FloatArray, strength: Float): Boolean

    // QR codes + EAN/UPC barcodes in [bitmap]. Returns interleaved raw bytes
    // {format0, payload0, format1, payload1, ...} or null; [cornersOut] receives 8
    // normalized floats (4 corners) per code and its size caps the number of codes.
    // Use CodeDecoder rather than calling this directly.
    external fun nativeDecodeCodes(bitmap: Bitmap, cornersOut: FloatArray, tryHarder: Boolean): Array<ByteArray>?

    // Open-palm detector (spread or relaxed fingers) next to the given face. [otherFaces]
    // holds normalized {left, top, right, bottom} per other person, whose skin is ignored.
    // Returns normalized {left, top, right, bottom} or null.
    external fun nativeDetectOpenPalm(
        bitmap: Bitmap,
        faceLeft: Float,
        faceTop: Float,
        faceRight: Float,
        faceBottom: Float,
        otherFaces: FloatArray?,
    ): FloatArray?
}
