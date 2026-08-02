package com.fearmikey.camera.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.gesture.GestureAnalyzer
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentValues
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.media.MediaActionSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CameraXProvider(
    private val context: Context,
    private val processor: ImageProcessor,
) : CaptureEngine {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var gestureAnalyzer: GestureAnalyzer? = null
    private var gestureTriggerEnabled: Boolean = false
    private var gestureListener: (() -> Unit)? = null
    private val gestureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    private var lastCameraSelector: CameraSelector? = null
    
    private val actionSound = MediaActionSound()

    init {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        lastLifecycleOwner = lifecycleOwner
        lastSurfaceProvider = surfaceProvider
        lastCameraSelector = cameraSelector

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
            
            val aspectStrategy = when (currentPrefs.aspectRatio) {
                DomainAspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                DomainAspectRatio.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                DomainAspectRatio.RATIO_FULL -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            }

            val captureResStrategy = if (currentPrefs.resolution == ResolutionMode.MP_50 || 
                currentPrefs.resolution == ResolutionMode.MP_12) {
                // Aim for high-res but allow fallback if binding fails.
                // 50MP (8192x6144) is the goal.
                ResolutionStrategy(
                    android.util.Size(8192, 6144), 
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            } else {
                null
            }

            val captureResolutionSelectorBuilder = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectStrategy)
            captureResStrategy?.let {
                captureResolutionSelectorBuilder.setResolutionStrategy(it)
            }
            val captureResolutionSelector = captureResolutionSelectorBuilder.build()

            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectStrategy)
                .setResolutionStrategy(ResolutionStrategy(
                    android.util.Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                ))
                .build()

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
            
            if (isProMode) {
                applyProConfigToBuilder(previewBuilder)
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(surfaceProvider)
            }

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureResolutionSelector)
            
            if (isProMode) {
                applyProConfigToBuilder(captureBuilder)
            }

            imageCapture = captureBuilder.build().also {
                it.flashMode = when (currentPrefs.flashMode) {
                    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                }
            }

            if (gestureTriggerEnabled) {
                gestureAnalyzer = GestureAnalyzer(context) {
                    gestureListener?.invoke()
                }
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(previewResolutionSelector)
                    .build()
                    .also {
                        it.setAnalyzer(gestureExecutor, gestureAnalyzer!!)
                    }
            } else {
                gestureAnalyzer?.close()
                gestureAnalyzer = null
                imageAnalysis = null
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                
                // On some devices, binding too many use cases at high res fails.
                // We only bind what's necessary for the current mode.
                val useCases = mutableListOf<UseCase>(preview, imageCapture!!)
                if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO) {
                    useCases.add(videoCapture!!)
                }
                imageAnalysis?.let { useCases.add(it) }
                
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                android.util.Log.i("CameraXProvider", "Binding SUCCESS: ${useCases.size} use cases")
            } catch (e: Exception) {
                android.util.Log.e("CameraXProvider", "Binding FAILED: ${e.message}")
                e.printStackTrace()
                
                // Emergency Fallback: Bind only Preview and ImageCapture with standard res
                if (currentMode != CameraMode.VIDEO) {
                    try {
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture!!
                        )
                        android.util.Log.i("CameraXProvider", "Fallback Binding SUCCESS")
                    } catch (e2: Exception) {
                        android.util.Log.e("CameraXProvider", "Fallback Binding FAILED: ${e2.message}")
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyProConfigToBuilder(builder: Any) {
        when (builder) {
            is Preview.Builder -> {
                val extender = Camera2Interop.Extender(builder)
                currentProConfig.iso?.let { extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it) }
                currentProConfig.shutterSpeedNs?.let { extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            }
            is ImageCapture.Builder -> {
                val extender = Camera2Interop.Extender(builder)
                currentProConfig.iso?.let { extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it) }
                currentProConfig.shutterSpeedNs?.let { extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            }
        }
    }

    override fun setMode(mode: CameraMode) {
        currentMode = mode
    }

    override fun applySettings(prefs: UserPreferences) {
        val needsRebind = (prefs.aspectRatio != currentPrefs.aspectRatio) ||
                (prefs.imageFormat != currentPrefs.imageFormat) ||
                (prefs.resolution != currentPrefs.resolution)
        
        currentPrefs = prefs
        
        if (needsRebind) {
            rebind()
        }
    }

    private fun rebind() {
        val owner = lastLifecycleOwner
        val surface = lastSurfaceProvider
        val selector = lastCameraSelector
        if (owner != null && surface != null && selector != null) {
            bindToLifecycle(owner, surface, selector)
        }
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        android.util.Log.i("CameraXProvider", "capturePhoto called: mode=$currentMode, resolution=${currentPrefs.resolution}")
        when {
            currentMode == CameraMode.PORTRAIT -> {
                android.util.Log.i("CameraXProvider", "Calling capturePortrait")
                capturePortrait(onSuccess, onError)
            }
            currentMode == CameraMode.SLOW_MO -> {
                android.util.Log.e("CameraXProvider", "Capture photo not supported in Slow Mo mode")
                onError(Exception("Capture photo not supported in Slow Mo mode."))
            }
            currentPrefs.resolution == ResolutionMode.MP_12 -> {
                android.util.Log.i("CameraXProvider", "Calling captureBinnedPhoto")
                captureBinnedPhoto(onSuccess, onError)
            }
            else -> {
                android.util.Log.i("CameraXProvider", "Calling captureSinglePhoto")
                captureSinglePhoto(onSuccess, onError)
            }
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

    private fun startRecording(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val videoCapture = videoCapture ?: return
        
        val name = "CAMERA_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        activeRecording = videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        onSuccess()
                    }
                    is VideoRecordEvent.Finalize -> {
                        val callback = videoCompletedCallback
                        if (!recordEvent.hasError()) {
                            callback?.invoke(recordEvent.outputResults.outputUri.toString())
                        } else {
                            activeRecording?.close()
                            activeRecording = null
                            recordEvent.cause?.let { onError(it as Exception) }
                        }
                        videoCompletedCallback = null
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
        val changed = config.iso != currentProConfig.iso || config.shutterSpeedNs != currentProConfig.shutterSpeedNs
        currentProConfig = config
        if (isProMode && changed) {
            rebind()
        }
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        val camera = camera ?: return null
        val info = camera.cameraInfo
        val zoomState = info.zoomState.value ?: return null
        
        val isoRange = android.util.Range(100, 3200)
        val shutterRange = android.util.Range(1_000_000L, 1_000_000_000L)
        
        val minZoom = zoomState.minZoomRatio
        val maxZoom = zoomState.maxZoomRatio
        
        val presets = mutableListOf<Float>()
        if (minZoom <= 0.6f) presets.add(0.6f)
        presets.add(1.0f)
        if (maxZoom >= 2.0f) presets.add(2.0f)
        if (maxZoom >= 5.0f) presets.add(5.0f)
        if (maxZoom >= 10.0f) presets.add(10.0f)

        return CameraHardwareCapabilities(
            isoRange = isoRange,
            shutterSpeedRange = shutterRange,
            minFocusDistance = 0.0f,
            zoomRange = android.util.Range(minZoom, maxZoom),
            zoomPresets = presets.asSequence().distinct().sorted().toList()
        )
    }

    override fun setProMode(enabled: Boolean) {
        if (isProMode != enabled) {
            isProMode = enabled
            rebind()
        }
    }

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun setFlashMode(flashMode: FlashMode) {
        imageCapture?.flashMode = when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    override fun setTorchEnabled(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    override fun playShutterSound() {
        if (currentPrefs.cameraSounds) {
            actionSound.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    override fun playTimerTickSound() {
        if (currentPrefs.cameraSounds) {
            // No direct MediaActionSound for tick, use a generic beep or FOCUS_COMPLETE
            actionSound.play(MediaActionSound.FOCUS_COMPLETE)
        }
    }

    override fun playStartRecordingSound() {
        if (currentPrefs.cameraSounds) {
            actionSound.play(MediaActionSound.START_VIDEO_RECORDING)
        }
    }

    override fun playStopRecordingSound() {
        if (currentPrefs.cameraSounds) {
            actionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    override fun setGestureTriggerEnabled(enabled: Boolean) {
        if (gestureTriggerEnabled != enabled) {
            gestureTriggerEnabled = enabled
            rebind()
        }
    }

    override fun setOnGestureListener(listener: () -> Unit) {
        this.gestureListener = listener
    }

    override fun focusAt(x: Float, y: Float) {
        val camera = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x, y, 0.1f) 
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    override fun unbind() {
        cameraProvider?.unbindAll()
    }

    private fun captureSinglePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: return
        
        val name = "CAMERA_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = currentPrefs.mirrorSelfies && lastCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: Uri.EMPTY
                    onSuccess(uri.toString())
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    private fun capturePortrait(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        captureSinglePhoto(
            onSuccess = { path ->
                Thread {
                    try {
                        val originalBitmap = BitmapFactory.decodeFile(path)
                        val portraitBitmap = processor.processPortrait(originalBitmap)
                        
                        val out = File(path).outputStream()
                        portraitBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.close()
                        
                        ContextCompat.getMainExecutor(context).execute {
                            onSuccess(path)
                        }
                    } catch (e: Exception) {
                        ContextCompat.getMainExecutor(context).execute {
                            onError(e)
                        }
                    }
                }.start()
            },
            onError = onError
        )
    }

    private fun captureBinnedPhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            android.util.Log.e("CameraXProvider", "captureBinnedPhoto: imageCapture is NULL")
            onError(Exception("Camera not ready"))
            return
        }
        
        android.util.Log.i("CameraXProvider", "captureBinnedPhoto: starting takePicture")
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    android.util.Log.i("CameraXProvider", "onCaptureSuccess: ${image.width}x${image.height} format=${image.format}")
                    scope.launch(Dispatchers.Default) {
                        try {
                            android.util.Log.i("CameraXProvider", "Calling processor.processPixelBinning")
                            val binnedBitmap = processor.processPixelBinning(image)
                            if (binnedBitmap != null) {
                                android.util.Log.i("CameraXProvider", "Binning SUCCESS, saving to MediaStore")
                                val uriString = saveBitmapToMediaStore(binnedBitmap)
                                android.util.Log.i("CameraXProvider", "Saved URI: $uriString")
                                ContextCompat.getMainExecutor(context).execute {
                                    onSuccess(uriString)
                                }
                            } else {
                                android.util.Log.e("CameraXProvider", "processor.processPixelBinning returned NULL")
                                ContextCompat.getMainExecutor(context).execute {
                                    onError(Exception("Pixel binning failed"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CameraXProvider", "Exception in binning scope", e)
                            ContextCompat.getMainExecutor(context).execute {
                                onError(e)
                            }
                        } finally {
                            image.close()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("CameraXProvider", "takePicture error: ${exception.message}", exception)
                    onError(exception)
                }
            }
        )
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): String {
        val name = "CAMERA_BIN_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return ""
            
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        
        return uri.toString()
    }

    private fun captureBurst(count: Int, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        captureSinglePhoto(onSuccess, onError)
    }
}
