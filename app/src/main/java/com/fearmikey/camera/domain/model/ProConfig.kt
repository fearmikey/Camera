package com.fearmikey.camera.domain.model

import android.util.Range
import androidx.compose.runtime.Immutable

/** Manual capture overrides. A null field means "auto". */
@Immutable
data class ProConfig(
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val whiteBalanceKelvin: Int? = null,
    /** Lens focus distance in diopters (0 = infinity, larger = closer). */
    val focusDistance: Float? = null,
    val isRawEnabled: Boolean = false
) {
    /** Manual ISO or shutter is set, so auto-exposure is off. */
    val hasManualExposure: Boolean get() = iso != null || shutterSpeedNs != null

    companion object {
        /** Fills in the missing half of a manual exposure (both engines use the same values). */
        const val MANUAL_DEFAULT_ISO = 400
        const val MANUAL_DEFAULT_SHUTTER_NS = 16_666_666L // 1/60 s
    }
}

@Immutable
data class CameraHardwareCapabilities(
    val isoRange: Range<Int>,
    val shutterSpeedRange: Range<Long>,
    /** Closest focus distance in diopters (0 means fixed-focus lens). */
    val minFocusDistance: Float,
    val zoomRange: Range<Float> = Range(1f, 10f),
    val zoomPresets: List<Float> = listOf(0.6f, 1.0f, 2.0f),
    /**
     * Zoom ratio -> zoom label. 0.7 on wide-angle selfie cameras, whose full field of view is
     * shown as ".7x" and a 1/0.7 crop as "1x" (like Google Camera); 1 elsewhere.
     */
    val zoomLabelScale: Float = 1f,
    /** Exposure compensation range in device steps (0..0 when unsupported). */
    val exposureRange: Range<Int> = Range(0, 0),
    /** EV per exposure compensation step. */
    val exposureStep: Float = 0f,
    /** True when the camera supports manual sensor control (manual ISO/shutter). */
    val supportsManualSensor: Boolean = false,
)
