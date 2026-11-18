package com.fearmikey.camera.data.engine.gesture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Rect
import com.fearmikey.camera.data.engine.processing.FaceLocator
import com.fearmikey.camera.data.engine.processing.NativeVisionBridge

/**
 * Palm-timer analyzer built entirely on FOSS components:
 *  1. Android's framework face detector gates detection to frames with a person.
 *  2. An OpenCV skin model, calibrated from that face, finds an open hand via
 *     convexity defects between extended fingers (see `vision_jni.cpp`).
 *
 * Expects RGBA_8888 frames from [ImageAnalysis].
 */
class GestureAnalyzer(
    private val onGestureTriggered: (GestureResult) -> Unit
) : ImageAnalysis.Analyzer {

    private sealed class State {
        object Searching : State()
        object HandDetected : State()
        object Cooldown : State()
    }

    private var frameCounter = 0
    private var consecutiveDetections = 0
    private var currentState: State = State.Searching
    private var lastTriggerTime = 0L
    private var lastFaceBox: Rect? = null
    private var lastFaceTimestamp = 0L

    override fun analyze(image: ImageProxy) {
        try {
            processFrame(image)
        } finally {
            image.close()
        }
    }

    /** Processes a frame without closing it (used by [com.fearmikey.camera.data.engine.analysis.FrameAnalysisRouter]). */
    fun processFrame(image: ImageProxy) {
        try {
            if (currentState is State.Cooldown &&
                System.currentTimeMillis() - lastTriggerTime > COOLDOWN_MS
            ) {
                currentState = State.Searching
            }

            frameCounter++
            if (frameCounter % FRAME_SKIP != 0) return

            val frame = image.toUprightSmallBitmap() ?: return
            try {
                process(frame)
            } finally {
                frame.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed: ${e.message}")
        }
    }

    private fun process(frame: Bitmap) {
        val now = System.currentTimeMillis()
        val face = FaceLocator.findLargestFace(frame)
        if (face != null) {
            lastFaceBox = Rect(face.left, face.top, face.right, face.bottom)
            lastFaceTimestamp = now
        } else if (now - lastFaceTimestamp > FACE_TIMEOUT_MS) {
            lastFaceBox = null
        }

        val faceBox = lastFaceBox
        if (faceBox == null) {
            consecutiveDetections = 0
            onGestureTriggered(GestureResult(null, null, false))
            return
        }

        val palm = NativeVisionBridge.nativeDetectOpenPalm(
            frame, faceBox.left, faceBox.top, faceBox.right, faceBox.bottom
        )
        if (palm == null) {
            consecutiveDetections = 0
            if (currentState is State.HandDetected) currentState = State.Searching
            onGestureTriggered(GestureResult(null, face?.let { faceBox }, false))
            return
        }

        val handBox = Rect(palm[0], palm[1], palm[2], palm[3])
        consecutiveDetections++
        if (currentState is State.Searching) currentState = State.HandDetected

        if (consecutiveDetections >= REQUIRED_CONSECUTIVE && currentState !is State.Cooldown) {
            currentState = State.Cooldown
            lastTriggerTime = now
            consecutiveDetections = 0
            onGestureTriggered(GestureResult(handBox, faceBox, true))
        } else {
            onGestureTriggered(GestureResult(handBox, faceBox, false))
        }
    }

    /** Converts the frame to an upright ARGB_8888 bitmap no larger than [ANALYSIS_MAX_SIDE]. */
    private fun ImageProxy.toUprightSmallBitmap(): Bitmap? {
        val raw = try {
            toBitmap()
        } catch (e: Exception) {
            return null
        }
        val rotation = imageInfo.rotationDegrees
        val scale = (ANALYSIS_MAX_SIDE.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        if (rotation == 0 && scale == 1f) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (out !== raw) raw.recycle()
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle() }
    }

    fun close() {
        // Nothing to release: no native models are held.
    }

    private companion object {
        const val TAG = "GestureAnalyzer"
        const val FRAME_SKIP = 3
        const val REQUIRED_CONSECUTIVE = 3
        const val COOLDOWN_MS = 8000L // Prevent re-triggering during/after the 3s timer
        const val FACE_TIMEOUT_MS = 2000L
        const val ANALYSIS_MAX_SIDE = 320
    }
}
