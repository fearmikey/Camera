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

class HybridCaptureEngine(
    private val cameraXProvider: CameraXProvider,
    private val camera2Provider: Camera2Provider
) : CaptureEngine {

    private var currentMode: CameraMode = CameraMode.PHOTO

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        // Bind both but only one will be active for the surface at a time
        cameraXProvider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector)
        camera2Provider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector)
    }

    override fun setMode(mode: CameraMode) {
        currentMode = mode
        cameraXProvider.setMode(mode)
        camera2Provider.setMode(mode)
    }

    override fun applySettings(prefs: UserPreferences) {
        cameraXProvider.applySettings(prefs)
        camera2Provider.applySettings(prefs)
        
        // Handle RAW mode
        if (prefs.saveMode == com.fearmikey.camera.domain.model.ImageSaveMode.RAW_JPEG) {
            // In a real app, we'd ensure the capture requests include RAW_SENSOR output
        }
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        when (currentMode) {
            CameraMode.PHOTO, CameraMode.VIDEO, CameraMode.PORTRAIT, CameraMode.PANORAMA, CameraMode.ADD_ME -> {
                cameraXProvider.capturePhoto(onSuccess, onError)
            }
            CameraMode.NIGHT_SIGHT, CameraMode.HDR -> {
                // Unbind CameraX to release the camera for Camera2 direct access
                cameraXProvider.unbind()
                
                camera2Provider.capturePhoto(
                    onSuccess = { path ->
                        // Restore CameraX after direct capture
                        // rebind() is not available here easily without state
                        onSuccess(path)
                    },
                    onError = { error ->
                        onError(error)
                    }
                )
            }
            CameraMode.SLOW_MO -> {
                onError(Exception("Capture photo not supported in Slow Mo mode. Use video."))
            }
        }
    }

    override fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        cameraXProvider.startPanorama(onSuccess, onError)
    }

    override fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        cameraXProvider.stopPanorama(onSuccess, onError)
    }

    override fun unbind() {
        cameraXProvider.unbind()
        camera2Provider.unbind()
    }

    override fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.startSlowMoVideo(onSuccess, onError)
        } else {
            cameraXProvider.startSlowMoVideo(onSuccess, onError)
        }
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit) {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.stopSlowMoVideo(onSuccess)
        } else {
            cameraXProvider.stopSlowMoVideo(onSuccess)
        }
    }

    override fun pauseVideo() {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.pauseVideo()
        } else {
            cameraXProvider.pauseVideo()
        }
    }

    override fun resumeVideo() {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.resumeVideo()
        } else {
            cameraXProvider.resumeVideo()
        }
    }

    override fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.captureVideoSnapshot(onSuccess, onError)
        } else {
            cameraXProvider.captureVideoSnapshot(onSuccess, onError)
        }
    }

    override fun updateManualConfig(config: ProConfig) {
        cameraXProvider.updateManualConfig(config)
        // camera2Provider.updateManualConfig(config)
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        // Prefer CameraX capabilities if available as they provide live zoom state
        return cameraXProvider.getCapabilities() ?: camera2Provider.getCapabilities()
    }

    override fun setProMode(enabled: Boolean) {
        cameraXProvider.setProMode(enabled)
        // Only CameraX handles Pro mode preview/config for now to avoid Surface conflicts
        // camera2Provider.setProMode(enabled) 
    }

    override fun setZoom(ratio: Float) {
        cameraXProvider.setZoom(ratio)
        // camera2Provider.setZoom(ratio)
    }

    override fun focusAt(x: Float, y: Float) {
        cameraXProvider.focusAt(x, y)
        // camera2Provider.focusAt(x, y)
    }

    override fun setFlashMode(flashMode: FlashMode) {
        cameraXProvider.setFlashMode(flashMode)
        camera2Provider.setFlashMode(flashMode)
    }

    override fun setTorchEnabled(enabled: Boolean) {
        cameraXProvider.setTorchEnabled(enabled)
        camera2Provider.setTorchEnabled(enabled)
    }

    override fun playShutterSound() {
        cameraXProvider.playShutterSound()
        camera2Provider.playShutterSound()
    }

    override fun playTimerTickSound() {
        cameraXProvider.playTimerTickSound()
        camera2Provider.playTimerTickSound()
    }

    override fun playStartRecordingSound() {
        cameraXProvider.playStartRecordingSound()
        camera2Provider.playStartRecordingSound()
    }

    override fun playStopRecordingSound() {
        cameraXProvider.playStopRecordingSound()
        camera2Provider.playStopRecordingSound()
    }

    override fun setGestureTriggerEnabled(enabled: Boolean) {
        cameraXProvider.setGestureTriggerEnabled(enabled)
        camera2Provider.setGestureTriggerEnabled(enabled)
    }

    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {
        cameraXProvider.setOnGestureListener(listener)
        camera2Provider.setOnGestureListener(listener)
    }
}
