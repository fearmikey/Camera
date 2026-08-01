package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.domain.model.CameraMode

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

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        when (currentMode) {
            CameraMode.PHOTO, CameraMode.VIDEO -> {
                cameraXProvider.capturePhoto(onSuccess, onError)
            }
            CameraMode.NIGHT, CameraMode.PORTRAIT, CameraMode.SLOW_MO -> {
                // In these modes, we would use Camera2 for advanced control
                // For now, falling back to CameraX until Camera2 is fully implemented
                cameraXProvider.capturePhoto(onSuccess, onError)
            }
        }
    }

    override fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        camera2Provider.startSlowMoVideo(onSuccess, onError)
    }

    override fun stopSlowMoVideo() {
        camera2Provider.stopSlowMoVideo()
    }
}
