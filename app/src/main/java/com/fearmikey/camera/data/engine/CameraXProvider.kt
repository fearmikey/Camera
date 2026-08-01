package com.fearmikey.camera.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentValues

class CameraXProvider(
    private val context: Context,
    private val processor: ImageProcessor,
) : CaptureEngine {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    private var lastCameraSelector: CameraSelector? = null

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

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectStrategy)
                .build()

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
            
            if (isProMode) {
                applyProConfigToBuilder(previewBuilder)
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(surfaceProvider)
            }

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
            
            if (isProMode) {
                applyProConfigToBuilder(captureBuilder)
            }

            imageCapture = captureBuilder.build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
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
        val oldMode = currentMode
        currentMode = mode
        if (((oldMode == CameraMode.VIDEO) || (mode == CameraMode.VIDEO)) && (oldMode != mode)) {
            // Re-bind to ensure VideoCapture is correctly configured if needed
            // Implementation pending
        }
    }

    override fun applySettings(prefs: UserPreferences) {
        val needsRebind = (prefs.aspectRatio != currentPrefs.aspectRatio) ||
                (prefs.imageFormat != currentPrefs.imageFormat)
        
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
        when (currentMode) {
            CameraMode.NIGHT -> captureBurst(3, onSuccess, onError)
            CameraMode.PORTRAIT -> capturePortrait(onSuccess, onError)
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
        
        // Default ranges if we can't get specifics for ISO/Shutter from CameraX easily without Camera2Interop
        val isoRange = android.util.Range(100, 3200)
        val shutterRange = android.util.Range(1_000_000L, 1_000_000_000L)
        
        val minZoom = zoomState.minZoomRatio
        val maxZoom = zoomState.maxZoomRatio
        
        // In a real app, we'd query physical cameras to find these.
        // For now, we'll suggest presets based on common ranges.
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

    override fun focusAt(x: Float, y: Float) {
        val camera = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x, y, 0.1f) // Slightly larger area for reliability
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
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

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

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
                // Run AI processing on a background thread to avoid UI jank
                Thread {
                    try {
                        val originalBitmap = BitmapFactory.decodeFile(path)
                        val portraitBitmap = processor.processPortrait(originalBitmap)
                        
                        // Overwrite the original file with the processed one
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

    private fun captureBurst(count: Int, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        // Burst capture not fully updated for MediaStore yet in this provider
        // Standard capture is preferred for now. Using 'count' for dummy loop
        var total = 0
        repeat(count) {
            total += 1
        }
        captureSinglePhoto(onSuccess, onError)
    }
}
