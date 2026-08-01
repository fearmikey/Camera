package com.fearmikey.camera.ui.camera

import android.net.Uri
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences

data class CameraUiState(
    val currentMode: CameraMode = CameraMode.PHOTO,
    val lastCapturedUri: Uri? = null,
    val isRecording: Boolean = false,
    val isProMode: Boolean = false,
    val isProcessing: Boolean = false,
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
    val focusPoint: androidx.compose.ui.geometry.Offset? = null
)
