package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences

interface CaptureEngine {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    )
    fun setMode(mode: CameraMode)
    fun applySettings(prefs: UserPreferences)
    fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun stopSlowMoVideo()
    fun updateManualConfig(config: ProConfig)
    fun getCapabilities(): CameraHardwareCapabilities?
    fun setProMode(enabled: Boolean)
    fun setZoom(ratio: Float)
    fun focusAt(x: Float, y: Float)
}
