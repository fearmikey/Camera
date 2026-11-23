package com.fearmikey.camera.data.engine.analysis

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.fearmikey.camera.domain.model.ScannedCode

/**
 * One live-scan update.
 *
 * @param codes Codes seen within the last few seconds, in first-seen order (drives the chip).
 * @param inView Texts of the codes found in this very frame (drives the outline overlay).
 * @param transform Maps the upright analysis frame to sensor space; combine it with
 *   PreviewView.outputTransform in a CoordinateTransform to draw outlines on the preview.
 * @param frameWidth Upright analysis frame width that [ScannedCode.corners] are normalized to.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
class CodeScanFrame(
    val codes: List<ScannedCode>,
    val inView: Set<String>,
    val transform: OutputTransform?,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    companion object {
        val EMPTY = CodeScanFrame(emptyList(), emptySet(), null, 0, 0)
    }
}

/**
 * Live QR/barcode scanning on CameraX analysis frames. Decodes every [FRAME_SKIP]th frame,
 * keeps codes "recent" for [CLEAR_AFTER_MS] after they were last seen, and reports a
 * [CodeScanFrame] whenever something is in view or the recent set changes.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
class CodeScanAnalyzer(private val onUpdate: (CodeScanFrame) -> Unit) {
    private class Tracked(var code: ScannedCode, var lastSeen: Long)

    private val recent = LinkedHashMap<String, Tracked>()
    private var frameCounter = 0
    private var hadCodesInView = false

    // Analysis rotation is fixed per binding, so the frame->sensor transform is cached.
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = false // ImageProxy.toBitmap() returns the full buffer
        isUsingRotationDegrees = true // corners are relative to the upright frame
    }
    private var cachedTransform: OutputTransform? = null
    private var cachedKey = 0L

    fun processFrame(image: ImageProxy) {
        frameCounter++
        if (frameCounter % FRAME_SKIP != 0) return
        val now = SystemClock.elapsedRealtime()
        val bitmap = try {
            image.toUprightBitmap(MAX_SIDE)
        } catch (e: Exception) {
            return
        }
        val found = try {
            CodeDecoder.decode(bitmap)
        } finally {
            bitmap.recycle()
        }

        for (code in found) {
            val tracked = recent[code.text]
            if (tracked != null) {
                tracked.code = code
                tracked.lastSeen = now
            } else {
                recent[code.text] = Tracked(code, now)
            }
        }
        val expired = recent.values.removeIf { now - it.lastSeen > CLEAR_AFTER_MS }
        // Nothing visible now or before and nothing expired: no update needed.
        if (found.isEmpty() && !hadCodesInView && !expired) return
        hadCodesInView = found.isNotEmpty()

        val rotation = image.imageInfo.rotationDegrees
        val rotated = rotation % 180 != 0
        onUpdate(
            CodeScanFrame(
                codes = recent.values.map { it.code },
                inView = found.mapTo(HashSet()) { it.text },
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
        const val FRAME_SKIP = 4
        const val MAX_SIDE = 1024
        const val CLEAR_AFTER_MS = 4000L
    }
}
