package com.fearmikey.camera.domain.model

import android.util.Range

data class ProConfig(
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val whiteBalanceKelvin: Int? = null,
    val focusDistance: Float? = null, // 0.0f to 1.0f (or based on lens characteristics)
    val isRawEnabled: Boolean = false
)

data class CameraHardwareCapabilities(
    val isoRange: Range<Int>,
    val shutterSpeedRange: Range<Long>,
    val minFocusDistance: Float
)
