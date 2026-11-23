package com.fearmikey.camera.domain.model

import androidx.compose.runtime.Immutable

/**
 * Zoom limits, preset buttons and starting zoom for one mode on one camera. All values are
 * camera zoom ratios (what CaptureEngine.setZoom takes); the UI multiplies them by the
 * camera's label scale (CameraHardwareCapabilities.zoomLabelScale) for display.
 */
@Immutable
data class ZoomPolicy(
    val min: Float,
    val max: Float,
    val presets: List<Float>,
    /** Zoom applied on entering the mode or switching cameras in it. */
    val defaultRatio: Float,
) {
    fun clamp(ratio: Float): Float = ratio.coerceIn(min, max)

    companion object {
        /** Rear Portrait: a short-telephoto crop like a portrait lens (as Google Camera does). */
        const val PORTRAIT_REAR_DEFAULT = 2f
        const val PORTRAIT_REAR_MAX = 3f

        /** Front Portrait: pinch up to this *label* (e.g. 2.9x on a .7x-wide selfie camera). */
        const val PORTRAIT_FRONT_MAX_LABEL = 2.9f

        /**
         * @param minZoom / maxZoom the camera's zoom ratio range.
         * @param lensPresets the camera's preset buttons (ZoomPresets.compute).
         * @param labelScale ratio -> label factor (0.7 on wide selfie cameras, else 1).
         */
        fun forMode(
            mode: CameraMode,
            isFront: Boolean,
            minZoom: Float,
            maxZoom: Float,
            lensPresets: List<Float>,
            labelScale: Float,
        ): ZoomPolicy {
            if (mode != CameraMode.PORTRAIT) {
                return ZoomPolicy(minZoom, maxZoom, lensPresets, 1f.coerceIn(minZoom, maxZoom))
            }
            return if (isFront) {
                // Full selfie field of view (".7x") by default, with the 1x crop one tap away.
                val hi = minOf(maxZoom, PORTRAIT_FRONT_MAX_LABEL / labelScale).coerceAtLeast(minZoom)
                ZoomPolicy(minZoom, hi, lensPresets.filter { it in minZoom..hi + 0.01f }, minZoom)
            } else {
                // 1x and 2x only; no ultrawide (distorts faces) and no long telephoto.
                val lo = maxOf(minZoom, 1f).coerceAtMost(maxZoom)
                val hi = minOf(maxZoom, PORTRAIT_REAR_MAX).coerceAtLeast(lo)
                val presets = listOf(1f, PORTRAIT_REAR_DEFAULT).filter { it in lo..hi + 0.01f }.ifEmpty { listOf(lo) }
                ZoomPolicy(lo, hi, presets, PORTRAIT_REAR_DEFAULT.coerceIn(lo, hi))
            }
        }
    }
}
