package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import java.nio.ByteBuffer

class NativePanoramaBridge {
    external fun nativeInit(blurThreshold: Double, matchRatio: Float): Long
    external fun nativeProcessFrame(
        enginePtr: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        format: Int,
        timestamp: Long
    ): Boolean
    external fun nativeFinalizeStitch(enginePtr: Long, outputBitmap: Bitmap): Boolean
    external fun nativeRelease(enginePtr: Long)

    companion object {
        init {
            System.loadLibrary("panorama_engine")
        }
    }
}
