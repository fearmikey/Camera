package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences

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
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        when (currentMode) {
            CameraMode.PHOTO, CameraMode.VIDEO, CameraMode.PORTRAIT -> {
                cameraXProvider.capturePhoto(onSuccess, onError)
            }
            CameraMode.NIGHT, CameraMode.HDR -> {
                camera2Provider.capturePhoto(onSuccess, onError)
            }
            CameraMode.SLOW_MO -> {
                onError(Exception("Capture photo not supported in Slow Mo mode. Use video."))
            }
        }
    }

    override fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.startSlowMoVideo(onSuccess, onError)
        } else {
            cameraXProvider.startSlowMoVideo(onSuccess, onError)
        }
    }

    override fun stopSlowMoVideo() {
        if (currentMode == CameraMode.SLOW_MO) {
            camera2Provider.stopSlowMoVideo()
        } else {
            cameraXProvider.stopSlowMoVideo()
        }
    }

    override fun updateManualConfig(config: ProConfig) {
        cameraXProvider.updateManualConfig(config)
        // camera2Provider.updateManualConfig(config)
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        return camera2Provider.getCapabilities()
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
}
