package com.fearmikey.camera.data.engine

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.analysis.CodeScanFrame
import com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.ModeCapabilities
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
    private var isProMode = false
    private var proConfig = ProConfig()
    /** Last low-light state from CameraX's scene monitor (drives Auto Night). Main thread. */
    private var sceneIsDark = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
        meteringPointProvider: MeteringPointProvider
    ) {
        isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        cameraXProvider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector, meteringPointProvider)
        camera2Provider.bindToLifecycle(lifecycleOwner, surfaceProvider, cameraSelector, meteringPointProvider)
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

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (com.fearmikey.camera.domain.model.CameraModes.isVideo(currentMode)) {
            onError(Exception("Use the record button in video modes."))
            return
        }
        // Same rule the UI uses (ModeCapabilities): RAW, Auto Night, HDR, flash, front camera.
        val pipeline = ModeCapabilities.pipelineFor(
            currentMode, isFrontCamera, currentPrefs, sceneIsDark,
            manualExposure = isProMode && proConfig.hasManualExposure,
        )
        if (!pipeline.usesCamera2) {
            cameraXProvider.capturePhoto(onSuccess, onError)
            return
        }
        // Release the device for direct Camera2 access, then always restore CameraX.
        cameraXProvider.unbind()
        val done: (String) -> Unit = { uri ->
            onMain {
                cameraXProvider.restore()
                onSuccess(uri)
            }
        }
        val failed: (Exception) -> Unit = { error ->
            onMain {
                cameraXProvider.restore()
                onError(error)
            }
        }
        camera2Provider.capture(pipeline, done, failed)
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

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (!slowMoActive) {
            cameraXProvider.stopSlowMoVideo(onSuccess, onError)
            return
        }
        slowMoActive = false
        camera2Provider.stopSlowMoVideo(
            onSuccess = { uri -> onMain { onSuccess(uri) } },
            onError = { error -> onMain { onError(error) } },
        )
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
        proConfig = config
        cameraXProvider.updateManualConfig(config)
        camera2Provider.updateManualConfig(config)
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        // CameraX provides live zoom state; Camera2 is a static fallback.
        return cameraXProvider.getCapabilities() ?: camera2Provider.getCapabilities()
    }

    override fun setProMode(enabled: Boolean) {
        isProMode = enabled
        cameraXProvider.setProMode(enabled)
        camera2Provider.setProMode(enabled)
    }

    override fun setZoom(ratio: Float) {
        cameraXProvider.setZoom(ratio)
        camera2Provider.setZoom(ratio)
    }

    override fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        cameraXProvider.focusAt(x, y, onComplete)
        // Camera2 remembers the point and meters/focuses there in its next capture.
        camera2Provider.focusAt(x, y)
    }

    override fun cancelFocus() {
        cameraXProvider.cancelFocus()
        camera2Provider.cancelFocus()
    }

    override fun setViewfinderAspectRatio(widthOverHeight: Float) {
        cameraXProvider.setViewfinderAspectRatio(widthOverHeight)
        camera2Provider.setViewfinderAspectRatio(widthOverHeight)
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
        // CameraX delivers on the main thread; remember the state for Auto Night routing.
        cameraXProvider.setOnLowLightListener { dark ->
            sceneIsDark = dark
            listener(dark)
        }
        camera2Provider.setOnLowLightListener(listener)
    }

    override fun setOnCaptureProgressListener(listener: (Float) -> Unit) {
        cameraXProvider.setOnCaptureProgressListener(listener)
        // Camera2 reports from its background thread.
        camera2Provider.setOnCaptureProgressListener { p -> onMain { listener(p) } }
    }

    override fun setOnCodeScanListener(listener: (CodeScanFrame) -> Unit) {
        cameraXProvider.setOnCodeScanListener(listener)
        camera2Provider.setOnCodeScanListener(listener)
    }

    override fun setOnPortraitFacesListener(listener: (PortraitFaceFrame) -> Unit) {
        cameraXProvider.setOnPortraitFacesListener(listener)
        camera2Provider.setOnPortraitFacesListener(listener)
    }
}
