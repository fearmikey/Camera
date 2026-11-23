package com.fearmikey.camera.data.engine.analysis

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.fearmikey.camera.data.engine.gesture.GestureAnalyzer

/**
 * CameraX allows one analyzer per ImageAnalysis, so this fans each frame out to
 * the enabled sub-analyzers (palm timer, QR/barcode scanner, Portrait face tracking)
 * and closes it afterwards.
 */
class FrameAnalysisRouter(
    private val gesture: GestureAnalyzer?,
    private val codes: CodeScanAnalyzer?,
    private val portraitFaces: PortraitFaceAnalyzer? = null,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            codes?.processFrame(image)
            portraitFaces?.processFrame(image)
            gesture?.processFrame(image)
        } finally {
            image.close()
        }
    }
}

/** Upright ARGB_8888 copy of the frame no larger than [maxSide]. */
fun ImageProxy.toUprightBitmap(maxSide: Int): Bitmap {
    val raw = toBitmap()
    val scale = (maxSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0 && scale == 1f && raw.config == Bitmap.Config.ARGB_8888) return raw
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (out !== raw) raw.recycle()
    return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle() }
}
