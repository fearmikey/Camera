package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.util.Log

/**
 * Portrait background blur without any ML runtime: the framework face detector
 * seeds an OpenCV GrabCut segmentation, and the background is blurred with a
 * normalized (halo-free) convolution. See `vision_jni.cpp`.
 */
class PortraitSegmenter {

    /**
     * Returns a copy of [source] with the background blurred. Falls back to the
     * unmodified image if processing fails.
     */
    fun applyBokeh(source: Bitmap, strength: Float = 1f): Bitmap {
        val working = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val face = FaceLocator.findLargestFace(working)
        val ok = NativeVisionBridge.nativePortraitBokeh(
            working,
            face?.left ?: 0f,
            face?.top ?: 0f,
            face?.right ?: 0f,
            face?.bottom ?: 0f,
            strength,
        )
        if (!ok) Log.w(TAG, "Portrait segmentation failed; saving original image")
        return working
    }

    private companion object {
        const val TAG = "PortraitSegmenter"
    }
}
