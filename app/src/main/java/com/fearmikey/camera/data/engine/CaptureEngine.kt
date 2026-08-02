package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode

interface CaptureEngine {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    )
    fun setMode(mode: CameraMode)
    fun applySettings(prefs: UserPreferences)
    fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit)
    fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit)
    fun stopSlowMoVideo(onSuccess: (String) -> Unit)
    fun pauseVideo()
    fun resumeVideo()
    fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun updateManualConfig(config: ProConfig)
    fun getCapabilities(): CameraHardwareCapabilities?
    fun setProMode(enabled: Boolean)
    fun setZoom(ratio: Float)
    fun focusAt(x: Float, y: Float)
    fun setFlashMode(flashMode: FlashMode)
    fun setTorchEnabled(enabled: Boolean)
    fun playShutterSound()
    fun playTimerTickSound()
    fun playStartRecordingSound()
    fun playStopRecordingSound()
    fun setGestureTriggerEnabled(enabled: Boolean)
    fun setOnGestureListener(listener: (GestureResult) -> Unit)
    fun unbind()
}
