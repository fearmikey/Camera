package com.fearmikey.camera.data.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.media.MediaActionSound
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.gesture.GestureAnalyzer
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.processing.PanoramaFrameAnalyzer
import com.fearmikey.camera.data.engine.processing.PanoramaSession
import com.fearmikey.camera.data.engine.processing.PanoramaTooShortException
import com.fearmikey.camera.data.repository.MediaSaver
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.PanoramaProgress
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio

class CameraXProvider(
    private val context: Context,
    private val processor: ImageProcessor,
    private val mediaSaver: MediaSaver,
    private val locationProvider: () -> Location?,
) : CaptureEngine {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var gestureAnalyzer: GestureAnalyzer? = null
    private var gestureTriggerEnabled: Boolean = false
    private var gestureListener: ((GestureResult) -> Unit)? = null
    private var panoramaProgressListener: ((PanoramaProgress) -> Unit)? = null
    private var cameraReadyListener: (() -> Unit)? = null
    private var lowLightListener: ((Boolean) -> Unit)? = null
    private var qrListener: ((String?) -> Unit)? = null
    @Volatile private var isLowLight = false
    private var lowLightFrameCounter = 0
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    private var targetRotation: Int = Surface.ROTATION_0
    private var exposureIndex: Int = 0

    /**
     * Active sweep. Read by the analyzer on [analysisExecutor]; it is only ever cleared on
     * that executor too, so a session can be stitched/closed without racing an analyzer.
     */
    @Volatile private var panoramaSession: PanoramaSession? = null
    private val isPanoramaActive: Boolean get() = panoramaSession != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    private var lastCameraSelector: CameraSelector? = null
    private var meteringPointFactoryProvider: (() -> MeteringPointFactory?)? = null

    private val actionSound = MediaActionSound()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private val isFrontCamera: Boolean
        get() = lastCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
        meteringPointFactoryProvider: () -> MeteringPointFactory?
    ) {
        lastLifecycleOwner = lifecycleOwner
        lastSurfaceProvider = surfaceProvider
        lastCameraSelector = cameraSelector
        this.meteringPointFactoryProvider = meteringPointFactoryProvider

        // Null out current camera to avoid stale capabilities during rebind
        camera = null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            bindUseCases(provider, lifecycleOwner, surfaceProvider, cameraSelector)
        }, mainExecutor)
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
    ) {
        val aspectStrategy = when (currentPrefs.aspectRatio) {
            DomainAspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            DomainAspectRatio.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            DomainAspectRatio.RATIO_FULL -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }

        // Aim for the sensor's full resolution (binning reduces it to ~12MP later).
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(8192, 6144),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(previewResolutionSelector)
        applyFocusAndStabilization(previewBuilder, isPreview = true)
        attachSceneMonitor(previewBuilder, cameraSelector)
        val preview = previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(captureResolutionSelector)
            .setTargetRotation(targetRotation)
        applyFocusAndStabilization(captureBuilder, isPreview = false)
        imageCapture = captureBuilder.build().also {
            it.flashMode = currentPrefs.effectiveFlashMode.toCameraX()
        }

        imageAnalysis = null
        val wantGesture = gestureTriggerEnabled && currentMode != CameraMode.PANORAMA &&
            !com.fearmikey.camera.domain.model.CameraModes.isVideo(currentMode)
        val wantQr = currentPrefs.scanQrCodes && currentMode == CameraMode.PHOTO
        if (wantGesture || wantQr) {
            gestureAnalyzer?.close()
            val gesture = if (wantGesture) GestureAnalyzer { result -> gestureListener?.invoke(result) } else null
            gestureAnalyzer = gesture
            val qr = if (wantQr) com.fearmikey.camera.data.engine.analysis.QrCodeAnalyzer { text ->
                mainExecutor.execute { qrListener?.invoke(text) }
            } else null
            val analyzer = com.fearmikey.camera.data.engine.analysis.FrameAnalysisRouter(gesture, qr)
            // QR codes need more pixels than palm detection.
            val analysisSize = if (wantQr) android.util.Size(1280, 720) else android.util.Size(640, 480)
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                analysisSize,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
        } else {
            gestureAnalyzer?.close()
            gestureAnalyzer = null
            qrListener?.let { l -> mainExecutor.execute { l(null) } }
        }

        if (currentMode == CameraMode.PANORAMA) {
            val analyzer = PanoramaFrameAnalyzer({ panoramaSession }) { progress ->
                mainExecutor.execute {
                    // Idle thumbnails only while idle; sweep progress only while sweeping.
                    if (progress.isIdlePreview != isPanoramaActive) panoramaProgressListener?.invoke(progress)
                }
            }
            // 4:3 keeps the full sensor field of view; the stitched height equals the
            // analysis frame's long side, so ask for as much as the stream will give.
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(PANO_ANALYSIS_W, PANO_ANALYSIS_H),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
        }

        val qualitySelector = when (currentPrefs.videoResolution) {
            com.fearmikey.camera.domain.model.VideoResolution.RES_4K ->
                QualitySelector.from(Quality.UHD, FallbackStrategy.lowerQualityThan(Quality.UHD))
            com.fearmikey.camera.domain.model.VideoResolution.RES_1080P ->
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder).also { it.targetRotation = targetRotation }

        try {
            provider.unbindAll()
            // Only bind what's necessary for the current mode.
            val useCases = mutableListOf<UseCase>(preview, imageCapture!!)
            if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO) {
                useCases.add(videoCapture!!)
            }
            imageAnalysis?.let { useCases.add(it) }

            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
            Log.i(TAG, "Binding SUCCESS: ${useCases.size} use cases")
        } catch (e: Exception) {
            Log.e(TAG, "Binding FAILED: ${e.message}", e)
            // Fallback: bind the essentials for the current mode.
            try {
                provider.unbindAll()
                val essential = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO) {
                    arrayOf(preview, videoCapture!!)
                } else {
                    arrayOf(preview, imageCapture!!)
                }
                camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *essential)
                Log.i(TAG, "Fallback Binding SUCCESS")
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback Binding FAILED: ${e2.message}")
            }
        }
        camera?.let { cam ->
            if (isProMode) applyProConfigLive()
            if (exposureIndex != 0) {
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                cam.cameraControl.setExposureCompensationIndex(exposureIndex.coerceIn(range.lower, range.upper))
            }
            cameraReadyListener?.invoke()
        }
    }

    /** Macro AF and (in video modes) video stabilization via Camera2 interop. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusAndStabilization(builder: Any, isPreview: Boolean) {
        val extender = when (builder) {
            is Preview.Builder -> Camera2Interop.Extender(builder)
            is ImageCapture.Builder -> Camera2Interop.Extender(builder)
            else -> return
        }
        if (currentPrefs.macroFocus == com.fearmikey.camera.domain.model.MacroMode.ON && !isFrontCamera) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_MACRO
            )
        }
        val videoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO
        if (isPreview && videoMode) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (currentPrefs.videoStabilization) {
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
            )
        }
    }

    /**
     * Applies manual controls to the live session without rebinding (so sliders
     * update the preview smoothly). Options are cleared when pro mode is off.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyProConfigLive() {
        val cam = camera ?: return
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        if (isPanoramaActive) {
            // Constant exposure and colour across the sweep avoids visible bands at seams.
            builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, true)
            builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        if (isProMode) {
            val cfg = currentProConfig
            if (cfg.iso != null || cfg.shutterSpeedNs != null) {
                // Manual exposure needs both values; fill the missing one with a sane default.
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF
                )
                builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, cfg.iso ?: 400)
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                    cfg.shutterSpeedNs ?: 16_666_666L
                )
            }
            cfg.focusDistance?.let {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
                )
                builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            cfg.whiteBalanceKelvin?.let { kelvin ->
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,
                    awbPresetFor(kelvin)
                )
            }
        }
        try {
            androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
                .setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to apply manual controls: ${e.message}")
        }
    }

    /** Maps a colour temperature to the closest standard AWB preset (supported on all devices). */
    private fun awbPresetFor(kelvin: Int): Int = when {
        kelvin < 3300 -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        kelvin < 4600 -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        kelvin < 6000 -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        else -> android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    }

    override fun setMode(mode: CameraMode) {
        if (mode == currentMode) return
        if (currentMode == CameraMode.PANORAMA) cancelPanorama()
        currentMode = mode
        // Use cases, analyzers (QR/palm/panorama), scene monitor and fps all depend on the mode.
        rebind()
    }

    override fun applySettings(prefs: UserPreferences) {
        val needsRebind = (prefs.aspectRatio != currentPrefs.aspectRatio) ||
            (prefs.imageFormat != currentPrefs.imageFormat) ||
            (prefs.resolution != currentPrefs.resolution) ||
            (prefs.videoResolution != currentPrefs.videoResolution) ||
            (prefs.videoStabilization != currentPrefs.videoStabilization) ||
            (prefs.macroFocus != currentPrefs.macroFocus) ||
            (prefs.videoFrameRate != currentPrefs.videoFrameRate) ||
            (prefs.scanQrCodes != currentPrefs.scanQrCodes) ||
            (prefs.lowLightSuggestion != currentPrefs.lowLightSuggestion)
        currentPrefs = prefs
        if (needsRebind) rebind()
    }

    /** Re-binds the last configuration, e.g. after Camera2 borrowed the device. */
    fun restore() = rebind()

    private fun rebind() {
        val owner = lastLifecycleOwner
        val surface = lastSurfaceProvider
        val selector = lastCameraSelector
        if (owner != null && surface != null && selector != null) {
            bindToLifecycle(owner, surface, selector, meteringPointFactoryProvider ?: { null })
        }
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        Log.i(TAG, "capturePhoto: mode=$currentMode, resolution=${currentPrefs.resolution}")
        when {
            currentMode == CameraMode.PANORAMA -> {
                if (!isPanoramaActive) startPanorama({}, onError) else stopPanorama(onSuccess, onError)
            }
            currentMode == CameraMode.PORTRAIT -> capturePortrait(onSuccess, onError)
            currentMode == CameraMode.SLOW_MO -> onError(Exception("Capture photo not supported in Slow Mo mode."))
            currentPrefs.resolution == ResolutionMode.MP_12 && !isFrontCamera -> captureBinnedPhoto(onSuccess, onError)
            else -> captureSinglePhoto(onSuccess, onError)
        }
    }

    private var videoCompletedCallback: ((String) -> Unit)? = null

    override fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (currentMode == CameraMode.VIDEO) {
            startRecording(onSuccess, onError)
        } else {
            onError(UnsupportedOperationException("Slow-mo not supported in CameraXProvider"))
        }
    }

    override fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (currentMode != CameraMode.PANORAMA || imageAnalysis == null) {
            onError(IllegalStateException("Panorama is not ready"))
            return
        }
        if (isPanoramaActive) {
            onError(IllegalStateException("Panorama already running"))
            return
        }
        panoramaSession = try {
            PanoramaSession(panoramaTanHalfFov())
        } catch (e: Throwable) {
            onError(Exception("Unable to start panorama", e))
            return
        }
        applyProConfigLive() // AE/AWB lock
        onSuccess()
    }

    override fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (!isPanoramaActive) {
            onError(IllegalStateException("Panorama not active"))
            return
        }
        val location = locationProvider()
        // Detach on the analysis executor: after this task no analyzer can touch the session.
        analysisExecutor.execute {
            val session = panoramaSession ?: return@execute
            panoramaSession = null
            mainExecutor.execute { applyProConfigLive() } // release AE/AWB lock
            scope.launch(Dispatchers.Default) {
                try {
                    val bitmap = session.stitch() ?: throw PanoramaTooShortException()
                    session.close()
                    val uri = try {
                        mediaSaver.saveJpeg(bitmap, "PANO", location)
                    } finally {
                        bitmap.recycle()
                    }
                    withContext(Dispatchers.Main) {
                        if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save panorama"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Panorama failed", e)
                    withContext(Dispatchers.Main) { onError(e) }
                } finally {
                    session.close()
                }
            }
        }
    }

    /** Discards a running sweep without stitching (mode change, unbind). */
    private fun cancelPanorama() {
        if (!isPanoramaActive) return
        analysisExecutor.execute {
            panoramaSession?.close()
            panoramaSession = null
        }
    }

    /**
     * tan(FOV/2) along the sensor's long side at the current zoom, from the lens focal
     * length and physical sensor size. The native engine uses it to build the cylinder.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun panoramaTanHalfFov(): Float {
        val cam = camera ?: return DEFAULT_TAN_HALF_FOV
        return try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val focal = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull()
            val sensor = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val zoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            if (focal == null || sensor == null || focal <= 0f) {
                DEFAULT_TAN_HALF_FOV
            } else {
                maxOf(sensor.width, sensor.height) / (2f * focal) / zoom.coerceAtLeast(0.1f)
            }
        } catch (e: Exception) {
            DEFAULT_TAN_HALF_FOV
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startRecording(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val videoCapture = videoCapture
        if (videoCapture == null) {
            onError(IllegalStateException("Video use case not ready"))
            return
        }

        val outputOptions = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(mediaSaver.newVideoValues())
            .apply { locationProvider()?.let { setLocation(it) } }
            .build()

        val pending = videoCapture.output.prepareRecording(context, outputOptions)
        if (currentPrefs.recordAudio && hasAudioPermission()) pending.withAudioEnabled()

        activeRecording = pending.start(mainExecutor) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> onSuccess()
                is VideoRecordEvent.Finalize -> {
                    val callback = videoCompletedCallback
                    videoCompletedCallback = null
                    if (!recordEvent.hasError() ||
                        recordEvent.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        recordEvent.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    ) {
                        callback?.invoke(recordEvent.outputResults.outputUri.toString())
                    } else {
                        activeRecording = null
                        val cause = recordEvent.cause
                        onError(cause as? Exception ?: Exception("Recording failed (${recordEvent.error})", cause))
                    }
                }
            }
        }
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit) {
        videoCompletedCallback = onSuccess
        activeRecording?.stop()
        activeRecording = null
    }

    override fun pauseVideo() {
        activeRecording?.pause()
    }

    override fun resumeVideo() {
        activeRecording?.resume()
    }

    override fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        captureSinglePhoto(onSuccess, onError)
    }

    override fun updateManualConfig(config: ProConfig) {
        if (config == currentProConfig) return
        currentProConfig = config
        if (isProMode) applyProConfigLive()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    override fun getCapabilities(): CameraHardwareCapabilities? {
        val camera = camera ?: return null
        val info = camera.cameraInfo
        val zoomState = info.zoomState.value ?: return null
        val c2 = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
        val isoRange = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: android.util.Range(100, 3200)
        val shutterRange = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: android.util.Range(1_000_000L, 1_000_000_000L)
        val minFocus = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val manualSensor = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val exposure = info.exposureState

        val minZoom = zoomState.minZoomRatio
        val maxZoom = zoomState.maxZoomRatio
        val isFront = info.lensFacing == CameraSelector.LENS_FACING_FRONT

        val presets = mutableListOf<Float>()
        if (minZoom < 0.95f) {
            presets.add((Math.round(minZoom * 10) / 10.0).toFloat())
        }
        presets.add(1.0f)
        if (!isFront) {
            if (maxZoom >= 2.0f) presets.add(2.0f)
            if (maxZoom >= 5.0f) presets.add(5.0f)
            if (maxZoom >= 10.0f) presets.add(10.0f)
        }

        return CameraHardwareCapabilities(
            isoRange = isoRange,
            // Cap the slider at 1s: longer exposures make the preview unusable.
            shutterSpeedRange = android.util.Range(
                shutterRange.lower,
                shutterRange.upper.coerceAtMost(1_000_000_000L).coerceAtLeast(shutterRange.lower)
            ),
            minFocusDistance = minFocus,
            zoomRange = android.util.Range(minZoom, maxZoom),
            zoomPresets = presets.distinct().sorted(),
            exposureRange = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange else android.util.Range(0, 0),
            exposureStep = exposure.exposureCompensationStep.toFloat(),
            supportsManualSensor = manualSensor,
        )
    }

    override fun setProMode(enabled: Boolean) {
        if (isProMode != enabled) {
            isProMode = enabled
            applyProConfigLive()
        }
    }

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun setFlashMode(flashMode: FlashMode) {
        imageCapture?.flashMode = flashMode.toCameraX()
    }

    override fun setTorchEnabled(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    override fun playShutterSound() {
        if (currentPrefs.cameraSounds) actionSound.play(MediaActionSound.SHUTTER_CLICK)
    }

    override fun playTimerTickSound() {
        if (currentPrefs.cameraSounds) actionSound.play(MediaActionSound.FOCUS_COMPLETE)
    }

    override fun playStartRecordingSound() {
        if (currentPrefs.cameraSounds) actionSound.play(MediaActionSound.START_VIDEO_RECORDING)
    }

    override fun playStopRecordingSound() {
        if (currentPrefs.cameraSounds) actionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    override fun setGestureTriggerEnabled(enabled: Boolean) {
        if (gestureTriggerEnabled != enabled) {
            gestureTriggerEnabled = enabled
            rebind()
        }
    }

    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {
        this.gestureListener = listener
    }

    override fun setOnPanoramaProgressListener(listener: (PanoramaProgress) -> Unit) {
        this.panoramaProgressListener = listener
    }

    override fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        // A running recording keeps its orientation; the next one picks this up.
        if (activeRecording == null) videoCapture?.targetRotation = rotation
    }

    override fun setOnCameraReadyListener(listener: () -> Unit) {
        cameraReadyListener = listener
    }

    override fun setOnLowLightListener(listener: (Boolean) -> Unit) {
        lowLightListener = listener
    }

    override fun setOnQrCodeListener(listener: (String?) -> Unit) {
        qrListener = listener
    }

    /**
     * Preview capture results drive the low-light (Night) suggestion; in video modes the
     * same interop hook pins the requested frame rate when the camera supports it.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun attachSceneMonitor(builder: Preview.Builder, selector: CameraSelector) {
        val extender = Camera2Interop.Extender(builder)
        val fps = currentPrefs.videoFrameRate.fps
        if (currentMode == CameraMode.VIDEO && fps != null && supportsFps(selector, fps)) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(fps, fps)
            )
        }
        val monitor = currentPrefs.lowLightSuggestion && currentMode == CameraMode.PHOTO &&
            selector != CameraSelector.DEFAULT_FRONT_CAMERA
        if (!monitor) {
            if (isLowLight) {
                isLowLight = false
                mainExecutor.execute { lowLightListener?.invoke(false) }
            }
            return
        }
        extender.setSessionCaptureCallback(object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: android.hardware.camera2.CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                if (++lowLightFrameCounter % 15 != 0) return
                val exposure = result[android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME] ?: return
                val iso = result[android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY] ?: return
                // Exposure "cost" in ISO-seconds; hysteresis avoids flicker at the threshold.
                val cost = exposure / 1e9 * iso
                val dark = if (isLowLight) cost > LOW_LIGHT_EXIT else cost > LOW_LIGHT_ENTER
                if (dark != isLowLight) {
                    isLowLight = dark
                    mainExecutor.execute { lowLightListener?.invoke(dark) }
                }
            }
        })
    }

    private fun supportsFps(selector: CameraSelector, fps: Int): Boolean = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val facing = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        manager.cameraIdList.map { manager.getCameraCharacteristics(it) }
            .firstOrNull { it[android.hardware.camera2.CameraCharacteristics.LENS_FACING] == facing }
            ?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.any { it.lower == fps && it.upper == fps } == true
    } catch (e: Exception) {
        false
    }

    override fun setExposureCompensation(index: Int) {
        exposureIndex = index
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        cam.cameraControl.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
    }

    override fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        val camera = camera ?: return

        val factory = meteringPointFactoryProvider?.invoke() ?: SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x, y, 0.15f)

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                onComplete(future.get().isFocusSuccessful)
            } catch (e: Exception) {
                onComplete(false)
            }
        }, mainExecutor)
    }

    override fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        cancelPanorama()
    }

    private fun captureSinglePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not ready"))
            return
        }

        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = currentPrefs.mirrorSelfies && isFrontCamera
            location = locationProvider()
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaSaver.newPhotoValues()
        ).setMetadata(metadata).build()

        capture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    if (uri != null) onSuccess(uri.toString()) else onError(Exception("Image was not saved"))
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    /** Captures in memory, decodes, and returns an upright bitmap (mirrored for selfies if requested). */
    private fun captureUprightBitmap(
        onBitmap: suspend (Bitmap) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not ready"))
            return
        }
        val mirror = currentPrefs.mirrorSelfies && isFrontCamera
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scope.launch(Dispatchers.Default) {
                    try {
                        val raw = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        val upright = if (rotation != 0 || mirror) {
                            val matrix = Matrix().apply {
                                postRotate(rotation.toFloat())
                                if (mirror) postScale(-1f, 1f)
                            }
                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                .also { if (it !== raw) raw.recycle() }
                        } else raw
                        onBitmap(upright)
                    } catch (e: Exception) {
                        image.close()
                        withContext(Dispatchers.Main) { onError(e) }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        })
    }

    private fun capturePortrait(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val location = locationProvider()
        captureUprightBitmap(onBitmap = { bitmap ->
            val portrait = processor.processPortrait(bitmap)
            val uri = mediaSaver.saveJpeg(portrait, "PORTRAIT", location)
            if (portrait !== bitmap) bitmap.recycle()
            portrait.recycle()
            withContext(Dispatchers.Main) {
                if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save portrait"))
            }
        }, onError = onError)
    }

    private fun captureBinnedPhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not ready"))
            return
        }
        val location = locationProvider()

        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scope.launch(Dispatchers.Default) {
                    try {
                        val binned = processor.processPixelBinning(image)
                            ?: throw Exception("Pixel binning failed")
                        val uri = mediaSaver.saveJpeg(binned, "IMG", location)
                        binned.recycle()
                        withContext(Dispatchers.Main) {
                            if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save photo"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Binned capture failed", e)
                        withContext(Dispatchers.Main) { onError(e) }
                    } finally {
                        image.close()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "takePicture error: ${exception.message}", exception)
                onError(exception)
            }
        })
    }

    private fun FlashMode.toCameraX(): Int = when (this) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

    private companion object {
        const val TAG = "CameraXProvider"
        // Panorama analysis stream (4:3). The stitched height is the long side.
        const val PANO_ANALYSIS_W = 1600
        const val PANO_ANALYSIS_H = 1200
        const val DEFAULT_TAN_HALF_FOV = 0.7f // ~70 degrees across the long side
        const val LOW_LIGHT_ENTER = 40.0 // e.g. ISO 1600 at 1/40 s
        const val LOW_LIGHT_EXIT = 20.0
    }
}
