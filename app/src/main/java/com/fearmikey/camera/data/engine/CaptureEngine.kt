package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.domain.model.CameraMode

interface CaptureEngine {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    )
    fun setMode(mode: CameraMode)
    fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun stopSlowMoVideo()
}
