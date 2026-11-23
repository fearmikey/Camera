package com.fearmikey.camera.data.engine.gesture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Rect
import com.fearmikey.camera.data.engine.processing.FaceLocator
import com.fearmikey.camera.data.engine.processing.NativeVisionBridge
import kotlin.math.roundToInt

/**
 * Palm-timer analyzer built entirely on FOSS components:
 *  1. Android's framework face detector gates detection to frames with a person.
 *  2. An OpenCV skin model, calibrated from that face, finds an open hand beside it by its
 *     shape: widely spread fingers on their own, or a relaxed open hand (fingers together or
 *     slightly apart) held at head height (see `vision_jni.cpp`). Other people's faces are
 *     masked out.
 *
 * Orientation: detection runs on the frame turned by the image rotation plus [deviceRotation],
 * so people are upright however the phone is held (the UI is locked to portrait, and both the
 * face detector and the "fingers point up" test need upright people). Reported boxes are
 * mapped back to the portrait viewfinder frame the HUD draws in.
 *
 * Distance: faces are searched at [NEAR_MAX_SIDE] px and, every other analyzed frame while
 * none is found, at [FAR_MAX_SIDE] px. A found face is then tracked in a window around it at
 * full analysis resolution, and the hand is searched in a crop around the face, also at full
 * resolution, scaled so the face is about [FACE_TARGET_PX] wide (the size the skin model and
 * finger-gap thresholds are tuned for). Only crops are rotated/scaled, never the whole frame.
 *
 * Expects RGBA_8888 frames from [ImageAnalysis].
 */
class GestureAnalyzer(
    /** Extra clockwise rotation (0/90/180/270) that turns the portrait frame upright in the world. */
    private val deviceRotation: () -> Int = { 0 },
    private val onGestureTriggered: (GestureResult) -> Unit,
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

    /** Last face, normalized in the upright (world) frame. */
    private var trackedFace: RectF? = null
    /** Other people found with it (same frame); the palm detector ignores their skin. */
    private var otherFaces: List<RectF> = emptyList()
    private var lastFaceTimestamp = 0L
    private var lastRotation = -1
    private var farPass = false

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

            val raw = try {
                image.toBitmap()
            } catch (e: Exception) {
                return
            }
            try {
                val extra = normalizeDegrees(deviceRotation())
                val rotation = normalizeDegrees(image.imageInfo.rotationDegrees + extra)
                process(raw, rotation, extra)
            } finally {
                raw.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed: ${e.message}")
        }
    }

    /**
     * [raw] is the frame as delivered (sensor orientation); turning it clockwise by [rotation]
     * makes it upright in the world, and [extra] is the part of that beyond the portrait frame.
     */
    private fun process(raw: Bitmap, rotation: Int, extra: Int) {
        if (rotation != lastRotation) {
            // The phone was turned: old coordinates no longer mean anything.
            lastRotation = rotation
            trackedFace = null
            otherFaces = emptyList()
            consecutiveDetections = 0
        }

        val now = System.currentTimeMillis()
        val faces = findFaces(raw, rotation)
        val face = faces.firstOrNull()
        if (face != null) {
            trackedFace = face
            otherFaces = faces.drop(1)
            lastFaceTimestamp = now
        } else if (now - lastFaceTimestamp > FACE_TIMEOUT_MS) {
            trackedFace = null
            otherFaces = emptyList()
        }

        val faceBox = trackedFace
        if (faceBox == null) {
            consecutiveDetections = 0
            onGestureTriggered(GestureResult(null, null, false))
            return
        }

        val handBox = findPalm(raw, rotation, faceBox)
        if (handBox == null) {
            consecutiveDetections = 0
            if (currentState is State.HandDetected) currentState = State.Searching
            onGestureTriggered(GestureResult(null, face?.let { toDisplay(faceBox, extra) }, false))
            return
        }

        consecutiveDetections++
        if (currentState is State.Searching) currentState = State.HandDetected

        val triggered = consecutiveDetections >= REQUIRED_CONSECUTIVE && currentState !is State.Cooldown
        if (triggered) {
            currentState = State.Cooldown
            lastTriggerTime = now
            consecutiveDetections = 0
        }
        onGestureTriggered(GestureResult(toDisplay(handBox, extra), toDisplay(faceBox, extra), triggered))
    }

    /** Faces (largest first) normalized in the upright frame; empty if none. */
    private fun findFaces(raw: Bitmap, rotation: Int): List<RectF> {
        trackedFace?.let { last ->
            // Track: a window around the last face, at up to full analysis resolution.
            val w = last.width()
            val h = last.height()
            val window = RectF(last.left - 2f * w, last.top - 1.5f * h, last.right + 2f * w, last.bottom + 1.5f * h)
            searchFaces(raw, rotation, window, TRACK_MAX_SIDE).takeIf { it.isNotEmpty() }?.let { return it }
        }
        farPass = !farPass
        val full = RectF(0f, 0f, 1f, 1f)
        return searchFaces(raw, rotation, full, NEAR_MAX_SIDE).takeIf { it.isNotEmpty() }
            ?: if (farPass) searchFaces(raw, rotation, full, FAR_MAX_SIDE) else emptyList()
    }

    private fun searchFaces(raw: Bitmap, rotation: Int, region: RectF, maxSide: Int): List<RectF> {
        val (crop, area) = cropUpright(raw, rotation, region, maxSide) ?: return emptyList()
        try {
            return FaceLocator.findFaces(crop, maxSide).map { area.subRect(it) }
        } finally {
            if (crop !== raw) crop.recycle()
        }
    }

    /** Open palm near [face] (both normalized in the upright frame), or null. */
    private fun findPalm(raw: Bitmap, rotation: Int, face: RectF): RectF? {
        val fw = face.width()
        val fh = face.height()
        // Hands are held beside or above the head, within a few face widths of it.
        val roi = RectF(face.left - 4f * fw, face.top - 2.5f * fh, face.right + 4f * fw, face.bottom + 2.5f * fh)
        val uprightW = if (rotation % 180 == 0) raw.width else raw.height
        val facePx = fw * uprightW
        if (facePx < 4f) return null
        val maxScale = FACE_TARGET_PX / facePx
        val (crop, area) = cropUpright(raw, rotation, roi, PALM_MAX_SIDE, maxScale) ?: return null
        try {
            fun RectF.inCrop() = RectF(
                (left - area.left) / area.width(), (top - area.top) / area.height(),
                (right - area.left) / area.width(), (bottom - area.top) / area.height(),
            )
            val f = face.inCrop()
            val others = otherFaces.map { it.inCrop() }
                .filter { it.right > 0f && it.left < 1f && it.bottom > 0f && it.top < 1f }
                .flatMap { listOf(it.left, it.top, it.right, it.bottom) }
                .toFloatArray()
            val palm = NativeVisionBridge.nativeDetectOpenPalm(
                crop, f.left, f.top, f.right, f.bottom, others.takeIf { it.isNotEmpty() }
            ) ?: return null
            return area.subRect(RectF(palm[0], palm[1], palm[2], palm[3]))
        } finally {
            if (crop !== raw) crop.recycle()
        }
    }

    /**
     * Cuts [region] (normalized, upright frame; clipped to the frame) out of [raw], turned
     * upright and scaled by min(1, [maxScale], [maxSide] / longer side). Returns the ARGB_8888
     * crop (may be [raw] itself) and the clipped region it covers, or null if it is too small.
     */
    private fun cropUpright(
        raw: Bitmap,
        rotation: Int,
        region: RectF,
        maxSide: Int,
        maxScale: Float = 1f,
    ): Pair<Bitmap, RectF>? {
        val rawRegion = rotateNormalized(region, 360 - rotation)
        val left = (rawRegion.left * raw.width).roundToInt().coerceIn(0, raw.width - 1)
        val top = (rawRegion.top * raw.height).roundToInt().coerceIn(0, raw.height - 1)
        val right = (rawRegion.right * raw.width).roundToInt().coerceIn(left + 1, raw.width)
        val bottom = (rawRegion.bottom * raw.height).roundToInt().coerceIn(top + 1, raw.height)
        val w = right - left
        val h = bottom - top
        if (w < MIN_CROP_PX || h < MIN_CROP_PX) return null

        val scale = minOf(1f, maxScale, maxSide.toFloat() / maxOf(w, h))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(raw, left, top, w, h, m, true)
        val argb = if (out.config == Bitmap.Config.ARGB_8888) {
            out
        } else {
            out.copy(Bitmap.Config.ARGB_8888, false).also { if (out !== raw) out.recycle() }
        }
        // The region actually covered, back in the upright frame.
        val covered = RectF(
            left.toFloat() / raw.width, top.toFloat() / raw.height,
            right.toFloat() / raw.width, bottom.toFloat() / raw.height,
        )
        return argb to rotateNormalized(covered, rotation)
    }

    /** Upright-frame box -> portrait viewfinder frame (undoes the [deviceRotation] part). */
    private fun toDisplay(r: RectF, extra: Int): Rect {
        val d = rotateNormalized(r, 360 - extra)
        return Rect(d.left, d.top, d.right, d.bottom)
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

        /** Whole-frame face search; FaceLocator's default. */
        const val NEAR_MAX_SIDE = 480
        /** Extra whole-frame pass for faces too small at [NEAR_MAX_SIDE] (people a few meters away). */
        const val FAR_MAX_SIDE = 960
        const val TRACK_MAX_SIDE = 480
        /** Face width the palm detector's thresholds and kernels work best at. */
        const val FACE_TARGET_PX = 110f
        const val PALM_MAX_SIDE = 640
        const val MIN_CROP_PX = 16

        fun normalizeDegrees(d: Int) = ((d % 360) + 360) % 360

        /** Rotates a normalized rect clockwise by a multiple of 90 degrees within the unit square. */
        fun rotateNormalized(r: RectF, degreesCw: Int): RectF = when (normalizeDegrees(degreesCw)) {
            90 -> RectF(1f - r.bottom, r.left, 1f - r.top, r.right)
            180 -> RectF(1f - r.right, 1f - r.bottom, 1f - r.left, 1f - r.top)
            270 -> RectF(r.top, 1f - r.right, r.bottom, 1f - r.left)
            else -> RectF(r)
        }

        /** Maps [inner], normalized within this rect, to this rect's coordinate space. */
        fun RectF.subRect(inner: RectF) = RectF(
            left + inner.left * width(),
            top + inner.top * height(),
            left + inner.right * width(),
            top + inner.bottom * height(),
        )
    }
}
