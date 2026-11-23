package com.fearmikey.camera.data.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.media.MediaActionSound
import android.os.SystemClock
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
import com.fearmikey.camera.data.engine.analysis.CodeScanAnalyzer
import com.fearmikey.camera.data.engine.analysis.CodeScanFrame
import com.fearmikey.camera.data.engine.analysis.FrameAnalysisRouter
import com.fearmikey.camera.data.engine.analysis.PortraitFaceAnalyzer
import com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame
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
import com.fearmikey.camera.domain.model.FrameGeometry
import com.fearmikey.camera.domain.model.PanoramaProgress
import com.fearmikey.camera.domain.model.PortraitAperture
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
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
    private var codeScanListener: ((CodeScanFrame) -> Unit)? = null
    private var portraitFacesListener: ((PortraitFaceFrame) -> Unit)? = null
    @Volatile private var isLowLight = false
    private var lowLightFrameCounter = 0
    private var lowLightPending = 0
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    /** Written on the main thread, read by the preview capture callback (camera thread). */
    @Volatile private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    /** Written on the main thread, also read by the palm-timer analyzer (analysis thread). */
    @Volatile private var targetRotation: Int = Surface.ROTATION_0
    private var exposureIndex: Int = 0
    /** Last requested zoom; re-applied after a rebind (CameraX resets zoom when the camera closes). */
    private var zoomRatio: Float = 1f
    /** Visible viewfinder width / height (setViewfinderAspectRatio); null until measured. */
    private var viewfinderWOverH: Float? = null

    /** What the current session was bound with; see [BindingSpec]. Null while unbound. */
    private var boundSpec: BindingSpec? = null
    private var boundPreview: Preview? = null
    private var boundUseCases: List<UseCase> = emptyList()

    /**
     * Active sweep. Read by the analyzer on [analysisExecutor]; it is only ever cleared on
     * that executor too, so a session can be stitched/closed without racing an analyzer.
     */
    @Volatile private var panoramaSession: PanoramaSession? = null
    private val isPanoramaActive: Boolean get() = panoramaSession != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    /** Also read on the analysis thread (palm timer, via [isFrontCamera]). */
    @Volatile private var lastCameraSelector: CameraSelector? = null
    private var meteringPointProvider: MeteringPointProvider? = null
    /** Zoom presets per camera id (lens metadata never changes; capabilities refresh on every bind). */
    /** Per camera id: zoom preset ratios and the zoom label scale (ZoomPresets). */
    private val zoomPresetCache = mutableMapOf<String, Pair<List<Float>, Float>>()

    private val actionSound = MediaActionSound().apply {
        // Preloaded so the chimes play without decode latency.
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
    /** uptimeMillis of the last start chime; a recording starting during it mutes until it ends. */
    private var startChimeAt = 0L
    private var chimeUnmuteJob: Job? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private val isFrontCamera: Boolean
        get() = lastCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
        meteringPointProvider: MeteringPointProvider
    ) {
        val cameraChanged = cameraSelector != lastCameraSelector || lifecycleOwner !== lastLifecycleOwner
        lastLifecycleOwner = lifecycleOwner
        lastSurfaceProvider = surfaceProvider
        lastCameraSelector = cameraSelector
        this.meteringPointProvider = meteringPointProvider

        if (cameraChanged) {
            // Avoid stale capabilities while the other camera opens; it starts at 1x.
            camera = null
            zoomRatio = 1f
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            bindUseCases(provider, lifecycleOwner, surfaceProvider, cameraSelector)
        }, mainExecutor)
    }

    /**
     * Build-time options of the [Preview]. While these stay the same the bound Preview is kept
     * across rebinds, so the camera device stays open and the viewfinder keeps its surface:
     * only the capture session is reconfigured (e.g. Photo <-> Video). Mode-dependent request
     * options (video stabilization, fps pin) are applied live in [applyProConfigLive] instead.
     */
    private data class PreviewSpec(
        val owner: LifecycleOwner,
        val surfaceProvider: Preview.SurfaceProvider,
        val selector: CameraSelector,
        val aspectRatio: DomainAspectRatio,
        val macro: Boolean,
        val facePriority: Boolean,
        val lowLightMonitor: Boolean,
    )

    /** Everything a bind depends on. Equal specs mean the bound session can be kept as is. */
    private data class BindingSpec(
        val preview: PreviewSpec,
        val video: Boolean,
        val videoResolution: com.fearmikey.camera.domain.model.VideoResolution,
        /** Front-camera recordings mirrored like the preview ("Keep selfies as seen"). */
        val mirrorVideo: Boolean,
        val gesture: Boolean,
        val codes: Boolean,
        val portraitFaces: Boolean,
        val panorama: Boolean,
    )

    private fun specFor(
        owner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        selector: CameraSelector,
    ): BindingSpec {
        val video = com.fearmikey.camera.domain.model.CameraModes.isVideo(currentMode)
        return BindingSpec(
            preview = PreviewSpec(
                owner = owner,
                surfaceProvider = surfaceProvider,
                selector = selector,
                aspectRatio = currentPrefs.aspectRatio,
                macro = currentPrefs.macroFocus == com.fearmikey.camera.domain.model.MacroMode.ON &&
                    selector != CameraSelector.DEFAULT_FRONT_CAMERA,
                facePriority = currentMode == CameraMode.PORTRAIT && supportsFacePriority(),
                // Always on for the rear camera: feeds both the Night chip and Auto Night, so
                // toggling either setting never rebinds.
                lowLightMonitor = selector != CameraSelector.DEFAULT_FRONT_CAMERA,
            ),
            // All video modes bind the same use cases (Slow motion / Time lapse record through
            // Camera2, but share the preview), so switching between them never rebinds.
            video = video,
            videoResolution = currentPrefs.videoResolution,
            mirrorVideo = video && currentPrefs.mirrorSelfies,
            gesture = gestureTriggerEnabled && currentMode != CameraMode.PANORAMA && !video,
            codes = currentPrefs.scanQrCodes && currentMode == CameraMode.PHOTO,
            portraitFaces = currentMode == CameraMode.PORTRAIT,
            panorama = currentMode == CameraMode.PANORAMA,
        )
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
    ) {
        val spec = specFor(lifecycleOwner, surfaceProvider, cameraSelector)
        val keptPreview = boundPreview?.takeIf {
            camera != null && boundSpec?.preview == spec.preview && provider.isBound(it)
        }

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

        val preview = keptPreview ?: run {
            val previewBuilder = Preview.Builder().setResolutionSelector(previewResolutionSelector)
            applyFocusOptions(previewBuilder)
            attachSceneMonitor(previewBuilder, spec.preview.lowLightMonitor)
            previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }
        }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(captureResolutionSelector)
            .setTargetRotation(targetRotation)
        applyFocusOptions(captureBuilder)
        imageCapture = captureBuilder.build().also {
            it.flashMode = currentPrefs.effectiveFlashMode.toCameraX()
        }
        applyCropAspect()

        imageAnalysis = null
        val wantGesture = spec.gesture
        val wantCodes = spec.codes
        val wantPortraitFaces = spec.portraitFaces
        // A new analyzer starts with an empty "recent codes" set, so clear the UI too.
        codeScanListener?.let { l -> mainExecutor.execute { l(CodeScanFrame.EMPTY) } }
        portraitFacesListener?.let { l -> mainExecutor.execute { l(PortraitFaceFrame.EMPTY) } }
        if (wantGesture || wantCodes || wantPortraitFaces) {
            gestureAnalyzer?.close()
            val gesture = if (wantGesture) {
                GestureAnalyzer(deviceRotation = ::gestureDeviceRotation) { result -> gestureListener?.invoke(result) }
            } else null
            gestureAnalyzer = gesture
            val codes = if (wantCodes) CodeScanAnalyzer { frame ->
                mainExecutor.execute { codeScanListener?.invoke(frame) }
            } else null
            val portraitFaces = if (wantPortraitFaces) PortraitFaceAnalyzer { frame ->
                mainExecutor.execute { portraitFacesListener?.invoke(frame) }
            } else null
            val analyzer = FrameAnalysisRouter(gesture, codes, portraitFaces)
            // QR codes and barcodes need the pixels; so does the palm timer for people a few
            // meters away (it only processes crops around the face, so the cost stays low).
            val analysisSize = when {
                wantCodes -> android.util.Size(1280, 720)
                wantGesture -> android.util.Size(1280, 960)
                else -> android.util.Size(640, 480)
            }
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
        }

        if (spec.panorama) {
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

        videoCapture = if (spec.video) {
            val qualitySelector = when (spec.videoResolution) {
                com.fearmikey.camera.domain.model.VideoResolution.RES_4K ->
                    QualitySelector.from(Quality.UHD, FallbackStrategy.lowerQualityThan(Quality.UHD))
                com.fearmikey.camera.domain.model.VideoResolution.RES_1080P ->
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            VideoCapture.Builder(recorder)
                .setMirrorMode(if (spec.mirrorVideo) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
                .setTargetRotation(targetRotation)
                .build()
        } else null

        // Only bind what's necessary for the current mode.
        val useCases = mutableListOf<UseCase>(preview, imageCapture!!)
        videoCapture?.let { useCases.add(it) }
        imageAnalysis?.let { useCases.add(it) }

        var bound: Camera? = null
        var degraded = false
        if (keptPreview != null) {
            // Fast path: swap only the other use cases. The Preview stays attached, so CameraX
            // reconfigures the capture session without closing and reopening the camera.
            try {
                val stale = boundUseCases.filter { it !== keptPreview }
                if (stale.isNotEmpty()) provider.unbind(*stale.toTypedArray())
                bound = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                Log.i(TAG, "Rebind (preview kept) SUCCESS: ${useCases.size} use cases")
            } catch (e: Exception) {
                Log.w(TAG, "Rebind with kept preview failed, doing a full bind: ${e.message}")
            }
        }
        if (bound == null) {
            try {
                provider.unbindAll()
                bound = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                Log.i(TAG, "Binding SUCCESS: ${useCases.size} use cases")
            } catch (e: Exception) {
                Log.e(TAG, "Binding FAILED: ${e.message}", e)
                // Fallback: bind the essentials for the current mode.
                try {
                    provider.unbindAll()
                    val essential = videoCapture?.let { listOf(preview, it) } ?: listOf(preview, imageCapture!!)
                    useCases.retainAll(essential)
                    degraded = true
                    bound = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                    Log.i(TAG, "Fallback Binding SUCCESS")
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback Binding FAILED: ${e2.message}")
                    useCases.clear()
                }
            }
        }
        camera = bound
        // A degraded (essentials-only) session never counts as matching, so the next change rebinds.
        boundSpec = if (bound != null && !degraded) spec else null
        boundPreview = if (bound != null) preview else null
        boundUseCases = useCases.toList()
        camera?.let { cam ->
            applyProConfigLive()
            if (zoomRatio != 1f) cam.cameraControl.setZoomRatio(zoomRatio)
            if (exposureIndex != 0) {
                val range = cam.cameraInfo.exposureState.exposureCompensationRange
                cam.cameraControl.setExposureCompensationIndex(exposureIndex.coerceIn(range.lower, range.upper))
            }
            cameraReadyListener?.invoke()
        }
    }

    /** Macro AF and Portrait face-priority 3A via Camera2 interop (build-time options). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusOptions(builder: Any) {
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
        // Portrait: let the camera's own face detection drive focus, exposure and white
        // balance (FACE_PRIORITY keeps the AE/AF/AWB modes we set, unlike other scene modes).
        if (currentMode == CameraMode.PORTRAIT && supportsFacePriority()) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
            )
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
            )
        }
    }

    /**
     * Applies live request options without rebinding: manual controls (so sliders update the
     * preview smoothly), the panorama AE/AWB lock, and in video modes stabilization and the
     * requested frame rate (so switching between photo/video modes keeps the bound Preview).
     * Camera2CameraControl replaces the whole set each call, so everything is rebuilt here.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyProConfigLive() {
        val cam = camera ?: return
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        if (com.fearmikey.camera.domain.model.CameraModes.isVideo(currentMode)) {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (currentPrefs.videoStabilization) {
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
            )
            val fps = currentPrefs.videoFrameRate.fps
            val selector = lastCameraSelector
            if (currentMode == CameraMode.VIDEO && fps != null && selector != null && supportsFps(selector, fps)) {
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(fps, fps)
                )
            }
        }
        if (isPanoramaActive) {
            // Constant exposure and color across the sweep avoids visible bands at seams.
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
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                    cfg.iso ?: ProConfig.MANUAL_DEFAULT_ISO
                )
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                    cfg.shutterSpeedNs ?: ProConfig.MANUAL_DEFAULT_SHUTTER_NS
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

    /** Maps a color temperature to the closest standard AWB preset (supported on all devices). */
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
        // The low-light chip only exists in Photo; re-arm it for the next visit.
        if (mode != CameraMode.PHOTO && isLowLight) {
            isLowLight = false
            mainExecutor.execute { lowLightListener?.invoke(false) }
        }
        // Rebinds only if the use cases/analyzers differ (never between video modes) and
        // otherwise just refreshes the live request options (stabilization, fps).
        rebind()
    }

    override fun applySettings(prefs: UserPreferences) {
        // Resolution (12 MP vs MAX) is chosen per shot in capturePhoto: no rebind needed.
        val needsRebind = (prefs.aspectRatio != currentPrefs.aspectRatio) ||
            (prefs.videoResolution != currentPrefs.videoResolution) ||
            (prefs.macroFocus != currentPrefs.macroFocus) ||
            (prefs.scanQrCodes != currentPrefs.scanQrCodes) ||
            (prefs.mirrorSelfies != currentPrefs.mirrorSelfies) // video mirror mode (build time)
        // Stabilization and frame rate are live request options (no rebind needed).
        val liveChanged = (prefs.videoStabilization != currentPrefs.videoStabilization) ||
            (prefs.videoFrameRate != currentPrefs.videoFrameRate)
        currentPrefs = prefs
        if (needsRebind) rebind() else if (liveChanged) applyProConfigLive()
    }

    /** Re-binds the last configuration, e.g. after Camera2 borrowed the device. */
    fun restore() = rebind()

    private fun rebind() {
        val owner = lastLifecycleOwner
        val surface = lastSurfaceProvider
        val selector = lastCameraSelector
        if (owner != null && surface != null && selector != null) {
            if (camera != null && specFor(owner, surface, selector) == boundSpec) {
                // Nothing the session depends on changed (e.g. Video <-> Slow motion):
                // keep streaming and only refresh the live request options.
                applyProConfigLive()
                mainExecutor.execute { cameraReadyListener?.invoke() }
                return
            }
            bindToLifecycle(owner, surface, selector, meteringPointProvider ?: { _, _, _ -> null })
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
    /** Error side of [stopSlowMoVideo]; when unset, a failed finalize goes to the start callback. */
    private var videoStopErrorCallback: ((Exception) -> Unit)? = null

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
        val withAudio = currentPrefs.recordAudio && hasAudioPermission()
        if (withAudio) pending.withAudioEnabled()

        val recording = pending.start(mainExecutor) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> onSuccess()
                is VideoRecordEvent.Finalize -> {
                    val callback = videoCompletedCallback
                    val stopError = videoStopErrorCallback
                    videoCompletedCallback = null
                    videoStopErrorCallback = null
                    if (!recordEvent.hasError() ||
                        recordEvent.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        recordEvent.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    ) {
                        callback?.invoke(recordEvent.outputResults.outputUri.toString())
                    } else {
                        activeRecording = null
                        val cause = recordEvent.cause
                        val error = if (recordEvent.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                            Exception("Video too short", cause)
                        } else {
                            cause as? Exception ?: Exception("Recording failed (${recordEvent.error})", cause)
                        }
                        // Report once: to whoever stopped the recording, else to the starter.
                        if (stopError != null) stopError(error) else onError(error)
                    }
                }
            }
        }
        activeRecording = recording
        if (withAudio) muteDuringStartChime(recording)
    }

    /**
     * The start chime plays through the speaker as the recorder starts, so the mic would
     * record it. Keep the audio track muted until the chime is over, then unmute.
     */
    private fun muteDuringStartChime(recording: Recording) {
        if (startChimeAt == 0L) return
        val remaining = startChimeAt + START_CHIME_MS - SystemClock.uptimeMillis()
        if (remaining <= 0) return
        recording.mute(true)
        chimeUnmuteJob?.cancel()
        chimeUnmuteJob = scope.launch {
            delay(remaining)
            if (activeRecording === recording) recording.mute(false)
        }
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val recording = activeRecording ?: run {
            onError(IllegalStateException("Not recording"))
            return
        }
        videoCompletedCallback = onSuccess
        videoStopErrorCallback = onError
        chimeUnmuteJob?.cancel()
        chimeUnmuteJob = null
        // Mute first so the stop chime can't leak into the clip's last buffered audio.
        recording.mute(true)
        recording.stop()
        activeRecording = null
    }

    /** CameraX's own PreviewView keeps running while it records. */
    override fun setRecordingPreview(target: RecordingPreviewTarget?) = Unit

    /** Slow motion is recorded by Camera2Provider. */
    override fun getSlowMotionFrameRates(): List<Int> = emptyList()

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

        // Presets follow the physical lenses (e.g. .5/1/2/5 on a Pixel Pro; no 10x stop).
        // Wide selfie cameras label their full view ".7x" (labelScale 0.7) and add a "1x" crop.
        val (presets, labelScale) = zoomPresetCache.getOrPut(c2.cameraId) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val scale = ZoomPresets.labelScale(manager, c2.cameraId, isFront)
            ZoomPresets.compute(manager, c2.cameraId, minZoom, maxZoom, isFront, scale) to scale
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
            zoomPresets = presets,
            zoomLabelScale = labelScale,
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
        zoomRatio = ratio
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
        if (currentPrefs.cameraSounds) {
            actionSound.play(MediaActionSound.START_VIDEO_RECORDING)
            startChimeAt = SystemClock.uptimeMillis()
        }
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

    /**
     * Extra clockwise rotation that turns the (portrait-upright) analysis frame upright in the
     * world when the phone is held sideways. Same relation CameraX uses for target rotations:
     * back camera -surface degrees, front camera +surface degrees.
     */
    private fun gestureDeviceRotation(): Int {
        val degrees = when (targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (isFrontCamera) degrees else (360 - degrees) % 360
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

    // CameraX captures are single-frame (multi-frame pipelines run in Camera2Provider).
    override fun setOnCaptureProgressListener(listener: (Float) -> Unit) = Unit

    override fun setOnCodeScanListener(listener: (CodeScanFrame) -> Unit) {
        codeScanListener = listener
    }

    override fun setOnPortraitFacesListener(listener: (PortraitFaceFrame) -> Unit) {
        portraitFacesListener = listener
    }

    /** Whether the selected camera offers the FACE_PRIORITY scene mode (cached per selector). */
    private fun supportsFacePriority(): Boolean {
        val selector = lastCameraSelector ?: return false
        facePriorityCache[selector]?.let { return it }
        val supported = try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val facing = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            } else {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
            manager.cameraIdList.map { manager.getCameraCharacteristics(it) }
                .firstOrNull { it[android.hardware.camera2.CameraCharacteristics.LENS_FACING] == facing }
                ?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.contains(android.hardware.camera2.CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) == true
        } catch (e: Exception) {
            false
        }
        facePriorityCache[selector] = supported
        return supported
    }

    private val facePriorityCache = HashMap<CameraSelector, Boolean>()

    /**
     * Preview capture results drive the low-light state (Night chip and Auto Night). The
     * callback is attached for the rear camera (independent of the mode, so the Preview can be
     * kept across mode switches) and only evaluates frames while in PHOTO. A change needs two
     * agreeing evaluations in a row, so AE settling after a rebind doesn't flip it.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun attachSceneMonitor(builder: Preview.Builder, monitor: Boolean) {
        val extender = Camera2Interop.Extender(builder)
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
                if (currentMode != CameraMode.PHOTO) return
                if (++lowLightFrameCounter % 15 != 0) return
                val exposure = result[android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME] ?: return
                val iso = result[android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY] ?: return
                // Post-RAW digital gain that AE adds in the dark (100 = none).
                val boost = result[android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST] ?: 100
                // Exposure "cost" in ISO-seconds; hysteresis avoids flicker at the threshold.
                val cost = exposure / 1e9 * iso * boost.coerceAtLeast(100) / 100.0
                val dark = if (isLowLight) cost > LOW_LIGHT_EXIT else cost > LOW_LIGHT_ENTER
                if (dark == isLowLight) {
                    lowLightPending = 0
                } else if (++lowLightPending >= 2) {
                    lowLightPending = 0
                    isLowLight = dark
                    mainExecutor.execute { lowLightListener?.invoke(dark) }
                }
            }
        })
    }

    /**
     * Whether the selected camera can pin [fps] (cached: it runs on the main thread for every
     * live Video option update, and each lookup reads all cameras' characteristics over binder).
     */
    private fun supportsFps(selector: CameraSelector, fps: Int): Boolean =
        fpsSupportCache.getOrPut(selector to fps) { querySupportsFps(selector, fps) }

    private val fpsSupportCache = HashMap<Pair<CameraSelector, Int>, Boolean>()

    private fun querySupportsFps(selector: CameraSelector, fps: Int): Boolean = try {
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

        // The viewfinder maps normalized coordinates through PreviewView's factory (handles
        // crop and rotation). Its factory takes view pixels, so passing 0..1 straight into it
        // used to meter the top-left corner. Fallback: normalized surface coordinates.
        val size = TAP_METERING_SIZE
        val point = meteringPointProvider?.invoke(x, y, size)
            ?: SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x, y, size)

        // AF and AE on the tapped spot (a dark subject is exposed for, which also gives AF
        // more contrast). The lock holds until the phone moves (CameraViewModel) or a new tap.
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .disableAutoCancel()
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

    override fun cancelFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    override fun setViewfinderAspectRatio(widthOverHeight: Float) {
        if (widthOverHeight <= 0f || widthOverHeight == viewfinderWOverH) return
        viewfinderWOverH = widthOverHeight
        applyCropAspect()
    }

    /**
     * Long/short crop that makes a photo match the viewfinder, or null when the capture stream
     * already has its shape: only the full-screen viewfinder crops (the 4:3 stream).
     */
    private fun photoCropAspect(): Float? =
        if (currentPrefs.aspectRatio == DomainAspectRatio.RATIO_FULL) {
            FrameGeometry.photoAspect(currentPrefs.aspectRatio, viewfinderWOverH)
        } else null

    /**
     * Crops photos saved by CameraX to the viewfinder. The ratio is given in the target
     * rotation's orientation (CameraX swaps it itself on later setTargetRotation calls). A new
     * ImageCapture (every bind, including a ratio change) starts uncropped.
     */
    private fun applyCropAspect() {
        val capture = imageCapture ?: return
        val aspect = photoCropAspect() ?: return
        val long = (aspect * CROP_RATIO_SCALE).roundToInt()
        val landscape = capture.targetRotation == Surface.ROTATION_90 || capture.targetRotation == Surface.ROTATION_270
        capture.setCropAspectRatio(
            if (landscape) android.util.Rational(long, CROP_RATIO_SCALE) else android.util.Rational(CROP_RATIO_SCALE, long)
        )
    }

    /** Center-crops an upright [bitmap] to the viewfinder (in-memory captures ignore the crop rect). */
    private fun cropToViewfinder(bitmap: Bitmap): Bitmap {
        val aspect = photoCropAspect() ?: return bitmap
        val box = FrameGeometry.centerCrop(bitmap.width, bitmap.height, aspect)
        if (box.width == bitmap.width && box.height == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    override fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        boundSpec = null
        boundPreview = null
        boundUseCases = emptyList()
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

    /**
     * Captures in memory, decodes, and returns an upright bitmap (mirrored for selfies if
     * requested), downscaled in the same pass to at most [maxPixels].
     */
    private fun captureUprightBitmap(
        onBitmap: suspend (Bitmap) -> Unit,
        onError: (Exception) -> Unit,
        maxPixels: Long = Long.MAX_VALUE,
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
                        val pixels = raw.width.toLong() * raw.height
                        val scale = if (pixels > maxPixels) {
                            kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat()
                        } else 1f
                        val upright = if (rotation != 0 || mirror || scale < 1f) {
                            val matrix = Matrix().apply {
                                postScale(scale, scale)
                                postRotate(rotation.toFloat())
                                if (mirror) postScale(-1f, 1f)
                            }
                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                .also { if (it !== raw) raw.recycle() }
                        } else raw
                        onBitmap(cropToViewfinder(upright))
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
        val strength = PortraitAperture.blurStrength(currentPrefs.portraitAperture)
        val keepOriginal = currentPrefs.portraitKeepOriginal
        captureUprightBitmap(onBitmap = { bitmap ->
            // The original goes first so the blurred shot is the newest (thumbnail) item.
            if (keepOriginal) mediaSaver.saveJpeg(bitmap, "PORTRAIT_ORIGINAL", location)
            val portrait = processor.processPortrait(bitmap, strength)
            val uri = mediaSaver.saveJpeg(portrait, "PORTRAIT", location)
            if (portrait !== bitmap) bitmap.recycle()
            portrait.recycle()
            withContext(Dispatchers.Main) {
                if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save portrait"))
            }
        }, onError = onError, maxPixels = PORTRAIT_MAX_PIXELS)
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
                            ?.let { cropToViewfinder(it) }
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
        // Tap-to-focus region as a fraction of the frame: small enough to pick a subject
        // (not the background behind it), big enough to hold some texture.
        const val TAP_METERING_SIZE = 0.1f
        // Panorama analysis stream (4:3). The stitched height is the long side.
        const val PANO_ANALYSIS_W = 1600
        const val PANO_ANALYSIS_H = 1200
        const val DEFAULT_TAN_HALF_FOV = 0.7f // ~70 degrees across the long side
        /** How long the recording stays muted after the start chime (chime length plus margin). */
        const val START_CHIME_MS = 800L
        const val LOW_LIGHT_ENTER = 40.0 // e.g. ISO 1600 at 1/40 s
        const val LOW_LIGHT_EXIT = 20.0
        // Portrait works on <= ~12.6MP: full-sensor (e.g. 50MP) bitmaps would cost ~200MB each
        // and the segmentation/blur run at fixed working sizes anyway.
        const val PORTRAIT_MAX_PIXELS = 12_600_000L
        // Crop ratios are passed to CameraX as integer Rationals: long side x 1000 / 1000.
        const val CROP_RATIO_SCALE = 1000
    }
}
