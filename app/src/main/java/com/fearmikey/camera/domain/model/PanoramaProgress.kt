package com.fearmikey.camera.domain.model

import android.graphics.Bitmap

/** What the user should do next during a panorama sweep (only warnings are shown as text). */
enum class PanoramaHint(val message: String) {
    START("Tap shutter and pause briefly on dots"),
    KEEP_GOING("Pause briefly on the dot"),
    SLOW_DOWN("Slow down"),
    TILT_UP("Move the dot into the circle"),
    TILT_DOWN("Move the dot into the circle"),
    LOST("Move back slowly"),
    COMPLETE("Full circle captured"),
}

/**
 * Panorama state emitted on the main thread: live sweep progress while capturing, or (with
 * [isIdlePreview]) just a live thumbnail of the view before the sweep starts.
 *
 * @param sweepDegrees horizontal angle covered so far.
 * @param verticalOffset drift of the view from the starting height, as a fraction of the
 *   frame height (positive = camera aims lower than at the start).
 * @param direction -1 = sweeping left, 1 = right, 0 = not yet known.
 * @param keyframeDx current view minus the last captured frame, fraction of frame width.
 * @param targetStep distance of the next dot from the last captured frame, fraction of frame width.
 * @param aligned the next dot is inside the target circle (pause to capture it).
 * @param preview stitched-so-far mosaic incl. the live view; the bitmap spans 360 degrees.
 * @param contentStart left edge of the captured part of [preview] (0..1).
 * @param contentEnd right edge of the captured part of [preview] (0..1).
 */
data class PanoramaProgress(
    val hint: PanoramaHint = PanoramaHint.START,
    val keyframes: Int = 0,
    val sweepDegrees: Float = 0f,
    val verticalOffset: Float = 0f,
    val frameX: Float = 0f,
    val frameWidth: Float = 0f,
    val direction: Int = 0,
    val keyframeDx: Float = 0f,
    val targetStep: Float = 0.33f,
    val aligned: Boolean = false,
    val preview: Bitmap? = null,
    val contentStart: Float = 0f,
    val contentEnd: Float = 0f,
    val isIdlePreview: Boolean = false,
)
