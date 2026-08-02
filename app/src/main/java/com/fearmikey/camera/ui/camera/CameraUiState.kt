package com.fearmikey.camera.ui.camera

import android.net.Uri
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import androidx.compose.runtime.Stable

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
    val timerCountdown: Int? = null,
    val captureProgress: Float = 0f,
    val zoomLevel: Float = 1.0f,
    val zoomPresets: List<Float> = listOf(0.6f, 1.0f, 2.0f),
    val proConfig: ProConfig = ProConfig(),
    val capabilities: CameraHardwareCapabilities? = null,
    val userPreferences: UserPreferences = UserPreferences(),
    val showSettingsSheet: Boolean = false,
    val horizonTilt: Float = 0f,
    val orientation: Int = 0,
    val isFrontCamera: Boolean = false,
    val autoShowLeveler: Boolean = false,
    val focusPoint: androidx.compose.ui.geometry.Offset? = null,
    val handBoundingBox: androidx.compose.ui.geometry.Rect? = null,
    val faceBoundingBox: androidx.compose.ui.geometry.Rect? = null,
    val isHandTimerPending: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF
)
