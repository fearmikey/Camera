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
)

@Immutable
data class CameraHardwareCapabilities(
    val isoRange: Range<Int>,
    val shutterSpeedRange: Range<Long>,
    /** Closest focus distance in diopters (0 means fixed-focus lens). */
    val minFocusDistance: Float,
    val zoomRange: Range<Float> = Range(1f, 10f),
    val zoomPresets: List<Float> = listOf(0.6f, 1.0f, 2.0f),
    /** Exposure compensation range in device steps (0..0 when unsupported). */
    val exposureRange: Range<Int> = Range(0, 0),
    /** EV per exposure compensation step. */
    val exposureStep: Float = 0f,
    /** True when the camera supports manual sensor control (manual ISO/shutter). */
    val supportsManualSensor: Boolean = false,
)
