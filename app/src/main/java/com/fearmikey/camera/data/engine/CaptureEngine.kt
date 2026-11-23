package com.fearmikey.camera.data.engine

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.analysis.CodeScanFrame
import com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame
import com.fearmikey.camera.data.engine.gesture.GestureResult
import androidx.camera.core.MeteringPoint
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.PanoramaProgress

/**
 * Maps a normalized (0..1) viewfinder position to a metering point whose `size` is a fraction
 * of the frame. Returns null while the viewfinder is not laid out yet.
 */
typealias MeteringPointProvider = (x: Float, y: Float, size: Float) -> MeteringPoint?

interface CaptureEngine {
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        meteringPointProvider: MeteringPointProvider = { _, _, _ -> null }
    )
    fun setMode(mode: CameraMode)
    fun applySettings(prefs: UserPreferences)
    fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit)
    fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit)
    /** Stops the recording; exactly one of [onSuccess] (saved URI) or [onError] is called. */
    fun stopSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun pauseVideo()
    fun resumeVideo()
    fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit)
    fun updateManualConfig(config: ProConfig)
    fun getCapabilities(): CameraHardwareCapabilities?
    fun setProMode(enabled: Boolean)
    fun setZoom(ratio: Float)
    /**
     * Tap-to-focus/meter at a normalized (0..1) viewfinder position. The lock holds until
     * [cancelFocus], another tap, or a rebind.
     */
    fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit = {})

    /** Drops a tap-focus lock and returns to continuous autofocus. */
    fun cancelFocus()

    /**
     * Width / height of the visible viewfinder (portrait UI). Photos are cropped to what it
     * shows (the full-screen ratio crops the 4:3 stream) and Camera2 maps taps through it.
     */
    fun setViewfinderAspectRatio(widthOverHeight: Float)
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

    /**
     * Progress (main thread, 0..1, non-decreasing per capture) of slow multi-frame captures
     * (Night / Auto Night, Long exposure, HDR): metering, frames received, merge, save.
     * Single-frame captures report nothing.
     */
    fun setOnCaptureProgressListener(listener: (Float) -> Unit)

    /**
     * Live QR/barcode scanning updates (main thread) while scanning is active (PHOTO mode and
     * prefs.scanQrCodes); [CodeScanFrame.EMPTY] when scanning stops.
     */
    fun setOnCodeScanListener(listener: (CodeScanFrame) -> Unit)

    /**
     * Live face boxes (main thread) while in PORTRAIT mode, for the viewfinder overlay;
     * [PortraitFaceFrame.EMPTY] whenever the camera rebinds.
     */
    fun setOnPortraitFacesListener(listener: (PortraitFaceFrame) -> Unit)

    fun unbind()
}
