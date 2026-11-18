package com.fearmikey.camera.data.engine

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.ImageSaveMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.UserPreferences

/**
 * Routes each operation to CameraX (preview, standard capture, video, panorama)
 * or Camera2 (Night, HDR, RAW, slow motion). Whenever Camera2 needs the device,
 * CameraX is unbound first and restored as soon as Camera2 is finished, so the
 * preview never stays frozen. All public callbacks are delivered on the main thread.
 */
class HybridCaptureEngine(
    private val cameraXProvider: CameraXProvider,
    private val camera2Provider: Camera2Provider
) : CaptureEngine {

    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var isFrontCamera = false
    private var slowMoActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
        meteringPointFactoryProvider: () -> androidx.camera.core.MeteringPointFactory?
    ) {
        isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        cameraXProvider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector, meteringPointFactoryProvider)
        camera2Provider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector, meteringPointFactoryProvider)
    }

    override fun setMode(mode: CameraMode) {
        currentMode = mode
        cameraXProvider.setMode(mode)
        camera2Provider.setMode(mode)
    }

    override fun applySettings(prefs: UserPreferences) {
        currentPrefs = prefs
        cameraXProvider.applySettings(prefs)
        camera2Provider.applySettings(prefs)
    }

    private fun usesCamera2ForPhoto(): Boolean = when (currentMode) {
        CameraMode.NIGHT_SIGHT, CameraMode.HDR, CameraMode.LONG_EXPOSURE -> true
        CameraMode.PHOTO -> !isFrontCamera &&
            (currentPrefs.saveMode == ImageSaveMode.RAW_JPEG || currentPrefs.hdrPhoto)
        else -> false
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (com.fearmikey.camera.domain.model.CameraModes.isVideo(currentMode)) {
            onError(Exception("Use the record button in video modes."))
            return
        }
        if (!usesCamera2ForPhoto()) {
            cameraXProvider.capturePhoto(onSuccess, onError)
            return
        }
        // Release the device for direct Camera2 access, then always restore CameraX.
        cameraXProvider.unbind()
        camera2Provider.capturePhoto(
            onSuccess = { uri ->
                onMain {
                    cameraXProvider.restore()
                    onSuccess(uri)
                }
            },
            onError = { error ->
                onMain {
                    cameraXProvider.restore()
                    onError(error)
                }
            }
        )
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
        slowMoActive = false
    }

    override fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (!com.fearmikey.camera.domain.model.CameraModes.isCamera2Video(currentMode)) {
            cameraXProvider.startSlowMoVideo(onSuccess, onError)
            return
        }
        cameraXProvider.unbind()
        slowMoActive = true
        camera2Provider.startSlowMoVideo(
            onSuccess = { onMain(onSuccess) },
            onError = { error ->
                onMain {
                    slowMoActive = false
                    cameraXProvider.restore()
                    onError(error)
                }
            }
        )
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit) {
        if (!slowMoActive) {
            cameraXProvider.stopSlowMoVideo(onSuccess)
            return
        }
        slowMoActive = false
        camera2Provider.stopSlowMoVideo { uri -> onMain { onSuccess(uri) } }
        cameraXProvider.restore()
    }

    override fun pauseVideo() {
        if (slowMoActive) camera2Provider.pauseVideo() else cameraXProvider.pauseVideo()
    }

    override fun resumeVideo() {
        if (slowMoActive) camera2Provider.resumeVideo() else cameraXProvider.resumeVideo()
    }

    override fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (slowMoActive) {
            camera2Provider.captureVideoSnapshot(onSuccess, onError)
        } else {
            cameraXProvider.captureVideoSnapshot(onSuccess, onError)
        }
    }

    override fun updateManualConfig(config: ProConfig) {
        cameraXProvider.updateManualConfig(config)
        camera2Provider.updateManualConfig(config)
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        // CameraX provides live zoom state; Camera2 is a static fallback.
        return cameraXProvider.getCapabilities() ?: camera2Provider.getCapabilities()
    }

    override fun setProMode(enabled: Boolean) {
        cameraXProvider.setProMode(enabled)
        camera2Provider.setProMode(enabled)
    }

    override fun setZoom(ratio: Float) {
        cameraXProvider.setZoom(ratio)
        camera2Provider.setZoom(ratio)
    }

    override fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        cameraXProvider.focusAt(x, y, onComplete)
    }

    override fun setFlashMode(flashMode: FlashMode) {
        cameraXProvider.setFlashMode(flashMode)
        camera2Provider.setFlashMode(flashMode)
    }

    override fun setTorchEnabled(enabled: Boolean) {
        cameraXProvider.setTorchEnabled(enabled)
    }

    override fun playShutterSound() = cameraXProvider.playShutterSound()
    override fun playTimerTickSound() = cameraXProvider.playTimerTickSound()
    override fun playStartRecordingSound() = cameraXProvider.playStartRecordingSound()
    override fun playStopRecordingSound() = cameraXProvider.playStopRecordingSound()

    override fun setGestureTriggerEnabled(enabled: Boolean) {
        cameraXProvider.setGestureTriggerEnabled(enabled)
        camera2Provider.setGestureTriggerEnabled(enabled)
    }

    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {
        cameraXProvider.setOnGestureListener(listener)
        camera2Provider.setOnGestureListener(listener)
    }

    override fun setOnPanoramaProgressListener(listener: (com.fearmikey.camera.domain.model.PanoramaProgress) -> Unit) {
        cameraXProvider.setOnPanoramaProgressListener(listener)
        camera2Provider.setOnPanoramaProgressListener(listener)
    }

    override fun setTargetRotation(rotation: Int) {
        cameraXProvider.setTargetRotation(rotation)
        camera2Provider.setTargetRotation(rotation)
    }

    override fun setOnCameraReadyListener(listener: () -> Unit) {
        cameraXProvider.setOnCameraReadyListener(listener)
        camera2Provider.setOnCameraReadyListener(listener)
    }

    override fun setExposureCompensation(index: Int) {
        cameraXProvider.setExposureCompensation(index)
        camera2Provider.setExposureCompensation(index)
    }

    override fun setOnLowLightListener(listener: (Boolean) -> Unit) {
        cameraXProvider.setOnLowLightListener(listener)
        camera2Provider.setOnLowLightListener(listener)
    }

    override fun setOnQrCodeListener(listener: (String?) -> Unit) {
        cameraXProvider.setOnQrCodeListener(listener)
        camera2Provider.setOnQrCodeListener(listener)
    }
}
