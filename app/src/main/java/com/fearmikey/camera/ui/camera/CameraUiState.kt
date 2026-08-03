package com.fearmikey.camera.ui.camera

import android.net.Uri
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset

@Stable
data class FocusState(
    val point: Offset,
    val isLocked: Boolean
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
    val panoramaMessage: String? = null,
    val panoramaYaw: Float = 0f,
    val panoramaPitch: Float = 0f,
    val panoramaTargets: List<Float> = emptyList(),
    val panoramaCapturedIndices: Set<Int> = emptySet(),
    val timerCountdown: Int? = null,
    val captureProgress: Float = 0f,
    val zoomLevel: Float = 1.0f,
    val zoomPresets: List<Float> = listOf(0.6f, 1.0f, 2.0f),
    val proConfig: ProConfig = ProConfig(),
    val capabilities: CameraHardwareCapabilities? = null,
    val userPreferences: UserPreferences = UserPreferences(),
    val showSettingsSheet: Boolean = false,
    val isFrontCamera: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF
)
