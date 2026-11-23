package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.util.Log

/**
 * Portrait background blur without any ML runtime: the framework face detector seeds an
 * OpenCV GrabCut segmentation (every face, for group shots), a guided filter snaps the
 * matte to real edges, and the background gets a lens-style disc blur with highlight bloom
 * and matched grain. See `PortraitEngine.cpp`.
 */
class PortraitSegmenter {

    /**
     * Returns [source] (or an ARGB_8888 copy of it) with the background blurred. [strength]
     * is 1.0 at f/2.8 (see PortraitAperture.blurStrength). Falls back to the unmodified
     * image if processing fails.
     */
    fun applyBokeh(source: Bitmap, strength: Float = 1f): Bitmap {
        val working = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val faces = FaceLocator.findFaces(working)
        val boxes = FloatArray(faces.size * 4)
        faces.forEachIndexed { i, f ->
            boxes[i * 4] = f.left
            boxes[i * 4 + 1] = f.top
            boxes[i * 4 + 2] = f.right
            boxes[i * 4 + 3] = f.bottom
        }
        val ok = NativeVisionBridge.nativePortraitBokeh(working, boxes, strength)
        if (!ok) Log.w(TAG, "Portrait segmentation failed; saving original image")
        return working
    }

    private companion object {
        const val TAG = "PortraitSegmenter"
    }
}
