package com.fearmikey.camera.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.processing.NativePanoramaBridge
import com.fearmikey.camera.data.engine.processing.PanoramaFrameAnalyzer
import com.fearmikey.camera.data.engine.gesture.GestureAnalyzer
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
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
import kotlinx.coroutines.withContext

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
    private var gestureListener: ((GestureResult) -> Unit)? = null
    private val gestureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    
    private val panoramaBridge = NativePanoramaBridge()
    private var panoramaEnginePtr: Long = 0
    private var isPanoramaActive = false
    
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
        
        // Null out current camera to avoid stale capabilities during rebind
        camera = null

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
                android.util.Log.i("CameraXProvider", "Palm Timer ENABLED - binding GestureAnalyzer")
                gestureAnalyzer?.close() // Ensure previous instance is cleaned up
                gestureAnalyzer = GestureAnalyzer(context) { result ->
                    gestureListener?.invoke(result)
                }
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(
                            android.util.Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        ))
                        .build())
                    .build()
                    .also {
                        it.setAnalyzer(gestureExecutor, gestureAnalyzer!!)
                    }
            } else {
                android.util.Log.i("CameraXProvider", "Palm Timer DISABLED")
                gestureAnalyzer?.close()
                gestureAnalyzer = null
                imageAnalysis = null
            }

            if (currentMode == CameraMode.PANORAMA) {
                if (panoramaEnginePtr == 0L) {
                    panoramaEnginePtr = panoramaBridge.nativeInit(100.0, 0.7f)
                }
                val analyzer = PanoramaFrameAnalyzer(panoramaBridge, panoramaEnginePtr, { isPanoramaActive }) {
                    // TODO: Notify UI to slow down
                    android.util.Log.w("CameraXProvider", "PANORAMA: SLOW DOWN!")
                }
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        ))
                        .build())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(gestureExecutor, analyzer)
                    }
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
        
        // Detect if we are on the back camera (Binning only allowed on back sensor)
        val isBackCamera = lastCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
        
        when {
            currentMode == CameraMode.PANORAMA -> {
                if (!isPanoramaActive) {
                    startPanorama({ /* Handle UI start */ }, onError)
                } else {
                    stopPanorama(onSuccess, onError)
                }
            }
            currentMode == CameraMode.PORTRAIT -> {
                android.util.Log.i("CameraXProvider", "Calling capturePortrait")
                capturePortrait(onSuccess, onError)
            }
            currentMode == CameraMode.SLOW_MO -> {
                android.util.Log.e("CameraXProvider", "Capture photo not supported in Slow Mo mode")
                onError(Exception("Capture photo not supported in Slow Mo mode."))
            }
            currentPrefs.resolution == ResolutionMode.MP_12 && isBackCamera -> {
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

    override fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (currentMode != CameraMode.PANORAMA) {
            onError(Exception("Not in Panorama mode"))
            return
        }
        isPanoramaActive = true
        onSuccess()
    }

    override fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (!isPanoramaActive) {
            onError(Exception("Panorama not active"))
            return
        }
        isPanoramaActive = false
        
        scope.launch(Dispatchers.Default) {
            try {
                // Stitching can take a few seconds, use a large enough bitmap
                // In a production app, we'd calculate the canvas size first
                val outputBitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
                val success = panoramaBridge.nativeFinalizeStitch(panoramaEnginePtr, outputBitmap)
                
                if (success) {
                    val uri = saveBitmapToMediaStore(outputBitmap)
                    withContext(Dispatchers.Main) {
                        onSuccess(uri)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError(Exception("Panorama stitching failed"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
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
        val isFront = info.lensFacing == CameraSelector.LENS_FACING_FRONT
        
        val presets = mutableListOf<Float>()
        
        // Add minimum zoom if it's less than 1.0 (typically wide angle)
        if (minZoom < 0.95f) {
            val roundedMin = (Math.round(minZoom * 10) / 10.0).toFloat()
            presets.add(roundedMin)
        }
        
        presets.add(1.0f)

        // For front camera, we usually only want wide and 1x
        // For back camera, we add telephoto presets
        if (!isFront) {
            if (maxZoom >= 2.0f) presets.add(2.0f)
            if (maxZoom >= 5.0f) presets.add(5.0f)
            if (maxZoom >= 10.0f) presets.add(10.0f)
        }

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

    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {
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
        camera = null
        if (panoramaEnginePtr != 0L) {
            panoramaBridge.nativeRelease(panoramaEnginePtr)
            panoramaEnginePtr = 0
        }
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
            onSuccess = { uriString ->
                Thread {
                    try {
                        val uri = uriString.toUri()
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        if (originalBitmap == null) {
                            throw Exception("Failed to decode bitmap from URI")
                        }

                        // Fix rotation before processing
                        val rotatedBitmap = fixRotation(uri, originalBitmap)

                        val portraitBitmap = processor.processPortrait(rotatedBitmap)
                        
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            portraitBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        
                        ContextCompat.getMainExecutor(context).execute {
                            onSuccess(uriString)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CameraXProvider", "Portrait processing failed", e)
                        ContextCompat.getMainExecutor(context).execute {
                            onError(e)
                        }
                    }
                }.start()
            },
            onError = onError
        )
    }

    private fun fixRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ExifInterface(inputStream)
        } else {
            return bitmap
        }
        inputStream.close()

        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
