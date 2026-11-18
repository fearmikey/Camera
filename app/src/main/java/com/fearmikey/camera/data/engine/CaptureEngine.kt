package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.gesture.GestureResult
import androidx.camera.core.MeteringPointFactory
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.PanoramaProgress

interface CaptureEngine {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        meteringPointFactoryProvider: () -> MeteringPointFactory? = { null }
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
    fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit = {})
    fun setFlashMode(flashMode: FlashMode)
    fun setTorchEnabled(enabled: Boolean)
    fun playShutterSound()
    fun playTimerTickSound()
    fun playStartRecordingSound()
    fun playStopRecordingSound()
    fun setGestureTriggerEnabled(enabled: Boolean)
    fun setOnGestureListener(listener: (GestureResult) -> Unit)

    /** Live sweep progress (main thread) between startPanorama and stopPanorama. */
    fun setOnPanoramaProgressListener(listener: (PanoramaProgress) -> Unit)

    /**
     * Device rotation as a [android.view.Surface] ROTATION_* constant. The UI is
     * portrait-locked, so this is what makes landscape photos/videos upright.
     */
    fun setTargetRotation(rotation: Int)

    /** Invoked on the main thread whenever a camera finishes binding (capabilities are then valid). */
    fun setOnCameraReadyListener(listener: () -> Unit)

    /** Exposure compensation in device steps (see CameraHardwareCapabilities.exposureRange). */
    fun setExposureCompensation(index: Int)

    /** Called (main thread) when the scene becomes dark enough to suggest Night mode, and when it brightens again. */
    fun setOnLowLightListener(listener: (Boolean) -> Unit)

    /** Called (main thread) with a decoded QR payload, or null when no code has been visible for a while. */
    fun setOnQrCodeListener(listener: (String?) -> Unit)

    fun unbind()
}
