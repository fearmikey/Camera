package com.fearmikey.camera.ui.camera

import android.net.Uri
import com.fearmikey.camera.domain.model.CameraMode

data class CameraUiState(
    val currentMode: CameraMode = CameraMode.PHOTO,
    val lastCapturedUri: Uri? = null,
    val isRecording: Boolean = false,
    val isProMode: Boolean = false,
    val zoomLevel: Float = 1.0f
)
