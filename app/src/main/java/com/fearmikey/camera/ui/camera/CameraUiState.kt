package com.fearmikey.camera.ui.camera

import android.net.Uri
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.CapturePipeline
import com.fearmikey.camera.domain.model.ModeCapabilities
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.ScannedCode
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset

@Stable
data class FocusState(
    val point: Offset,
    val isLocked: Boolean
)

/**
 * Device attitude for the levelers (from CameraViewModel's rotation sensor). The UI is
 * portrait-locked, so screen axes equal device axes.
 */
@Stable
data class LevelState(
    /** Roll in the screen plane, degrees: 0 = upright portrait, +-90 = landscape. */
    val roll: Float = 0f,
    /** Held in landscape (not pointing up/down): shows the horizon leveler. */
    val isLandscape: Boolean = false,
    /** Camera has pointed (nearly) straight down for a moment: shows the top-down leveler. */
    val isFacingDown: Boolean = false,
    /** Tilt away from straight down, degrees: + = right edge raised. */
    val downTiltX: Float = 0f,
    /** Tilt away from straight down, degrees: + = top edge raised. */
    val downTiltY: Float = 0f,
)

/**
 * Live zoom (CameraViewModel.zoomState). Kept out of [CameraUiState] because it changes every
 * frame while pinching or gliding to a preset; only the zoom row and the pinch handler read it,
 * so those updates don't recompose the whole camera screen.
 */
data class ZoomUiState(
    val level: Float = 1.0f,
    /** Preset the zoom is gliding to (CameraViewModel.animateZoomTo); null when not animating. */
    val target: Float? = null,
)

@Stable
data class CameraUiState(
    val captureType: CaptureType = CaptureType.PHOTO,
    val currentMode: CameraMode = CameraMode.PHOTO,
    val lastCapturedUri: Uri? = null,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingDuration: String = "00:00",
    val isProMode: Boolean = false,
    val isProcessing: Boolean = false,
    val backgroundTaskMessage: String? = null,
    val isPanoramaCapturing: Boolean = false,
    val timerCountdown: Int? = null,
    /** 0..1 progress of the running multi-frame capture (CaptureEngine.setOnCaptureProgressListener). */
    val captureProgress: Float = 0f,
    /** Preset buttons for the current mode and camera (ZoomPolicy.presets). */
    val zoomPresets: List<Float> = listOf(0.6f, 1.0f, 2.0f),
    /** Zoom ratio -> label factor (CameraHardwareCapabilities.zoomLabelScale, e.g. 0.7 -> ".7x"). */
    val zoomLabelScale: Float = 1f,
    val proConfig: ProConfig = ProConfig(),
    val capabilities: CameraHardwareCapabilities? = null,
    val userPreferences: UserPreferences = UserPreferences(),
    val showSettingsSheet: Boolean = false,
    val isFrontCamera: Boolean = false,
    /** Exposure compensation in device steps (see capabilities.exposureStep). */
    val exposureIndex: Int = 0,
    /** Scene is dark: offer to switch to Night mode. */
    val lowLightSuggested: Boolean = false,
    /** Raw scene state from the engine's low-light monitor (rear camera, Photo mode). */
    val isLowLight: Boolean = false,
    /** Pipeline of the capture in progress, snapshotted at the shutter press; null when idle. */
    val capturePipeline: CapturePipeline? = null,
    /** QR codes/barcodes seen in the last few seconds (minus dismissed ones), first-seen order. */
    val scannedCodes: List<ScannedCode> = emptyList(),
    /** Codes shown in the result sheet (live scan snapshot or a picked image), null when closed. */
    val codeSheet: List<ScannedCode>? = null,
    /** Decoding a picked image is in progress. */
    val isScanningImage: Boolean = false,
) {
    /**
     * What the current mode, camera and settings support, and which pipeline the next shot
     * runs. Same rule as HybridCaptureEngine's routing (ModeCapabilities).
     */
    val modeCaps: ModeCapabilities
        get() = ModeCapabilities.forMode(
            currentMode, isFrontCamera, userPreferences, isLowLight,
            manualExposure = isProMode && proConfig.hasManualExposure,
        )

    /** The next Photo shot will use the Night pipeline (flash setting Auto Night in a dark scene). */
    val autoNightActive: Boolean
        get() = modeCaps.autoNightActive

    /** The capture in progress reports [captureProgress] (Night / Auto Night, Long exposure, HDR). */
    val showsCaptureProgress: Boolean
        get() = capturePipeline?.reportsProgress == true
}
