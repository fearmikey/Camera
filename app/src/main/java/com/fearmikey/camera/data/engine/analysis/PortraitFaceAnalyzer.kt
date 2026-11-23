package com.fearmikey.camera.data.engine.analysis

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.fearmikey.camera.data.engine.processing.FaceLocator

/**
 * One live Portrait-mode face update.
 *
 * @param faces Head boxes normalized (0..1) to the upright analysis frame, largest first.
 * @param transform Maps the upright analysis frame to sensor space; combine it with
 *   PreviewView.outputTransform in a CoordinateTransform to draw on the preview.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
class PortraitFaceFrame(
    val faces: List<RectF>,
    val transform: OutputTransform?,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    companion object {
        val EMPTY = PortraitFaceFrame(emptyList(), null, 0, 0)
    }
}

/**
 * Portrait mode's live face tracking: runs the same framework face detector the capture
 * pipeline uses on every [FRAME_SKIP]th analysis frame, so the viewfinder shows which people
 * will be kept sharp. Reports every detection while faces are visible and once when they leave.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
class PortraitFaceAnalyzer(private val onUpdate: (PortraitFaceFrame) -> Unit) {
    private var frameCounter = 0
    private var hadFaces = true // report the first (possibly empty) result

    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = false // ImageProxy.toBitmap() returns the full buffer
        isUsingRotationDegrees = true // boxes are relative to the upright frame
    }
    private var cachedTransform: OutputTransform? = null
    private var cachedKey = 0L

    fun processFrame(image: ImageProxy) {
        if (frameCounter++ % FRAME_SKIP != 0) return
        val bitmap = try {
            image.toUprightBitmap(MAX_SIDE)
        } catch (e: Exception) {
            return
        }
        val faces = try {
            FaceLocator.findFaces(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (faces.isEmpty() && !hadFaces) return
        hadFaces = faces.isNotEmpty()

        val rotation = image.imageInfo.rotationDegrees
        val rotated = rotation % 180 != 0
        onUpdate(
            PortraitFaceFrame(
                faces = faces,
                transform = transformFor(image, rotation),
                frameWidth = if (rotated) image.height else image.width,
                frameHeight = if (rotated) image.width else image.height,
            )
        )
    }

    private fun transformFor(image: ImageProxy, rotation: Int): OutputTransform? {
        val key = (image.width.toLong() shl 32) or (image.height.toLong() shl 16) or rotation.toLong()
        if (cachedTransform == null || key != cachedKey) {
            cachedTransform = try {
                transformFactory.getOutputTransform(image)
            } catch (e: Exception) {
                null
            }
            cachedKey = key
        }
        return cachedTransform
    }

    private companion object {
        const val FRAME_SKIP = 3
        const val MAX_SIDE = 480
    }
}
