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
import com.fearmikey.camera.domain.model.ImageFormat as DomainImageFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.provider.MediaStore
import android.content.ContentValues

class CameraXProvider(
    private val context: Context,
    private val processor: ImageProcessor
) : CaptureEngine {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: androidx.camera.core.Camera? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentProConfig: ProConfig = ProConfig()
    private var isProMode: Boolean = false
    
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    private var lastCameraSelector: CameraSelector? = null

    init {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        lastLifecycleOwner = lifecycleOwner
        lastSurfaceProvider = surfaceProvider
        lastCameraSelector = cameraSelector

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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
        if ((oldMode == CameraMode.VIDEO || mode == CameraMode.VIDEO) && oldMode != mode) {
            // Re-bind to ensure VideoCapture is correctly configured if needed
            // Actually, we bind it anyway above, so it should be fine.
        }
    }

    override fun applySettings(prefs: UserPreferences) {
        val needsRebind = prefs.aspectRatio != currentPrefs.aspectRatio ||
                prefs.imageFormat != currentPrefs.imageFormat
        
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

    override fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (currentMode == CameraMode.VIDEO) {
            startRecording(onSuccess, onError)
        } else {
            onError(UnsupportedOperationException("Slow-mo not supported in CameraXProvider"))
        }
    }

    private fun startRecording(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val videoCapture = videoCapture ?: return
        
        val name = "CAMERA_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
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
                        // Handle start if needed
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            onSuccess(recordEvent.outputResults.outputUri.toString())
                        } else {
                            activeRecording?.close()
                            activeRecording = null
                            onError(Exception("Video recording failed: ${recordEvent.error}"))
                        }
                    }
                }
            }
    }

    override fun stopSlowMoVideo() {
        activeRecording?.stop()
        activeRecording = null
    }

    override fun updateManualConfig(config: ProConfig) {
        currentProConfig = config
        if (isProMode) {
            rebind()
        }
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        return null
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
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun captureSinglePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: return
        val photoFile = createPhotoFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSuccess(photoFile.absolutePath)
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
        val capturedPaths = mutableListOf<String>()
        var capturesCompleted = 0

        for (i in 0 until count) {
            captureSinglePhoto(
                onSuccess = { path ->
                    capturedPaths.add(path)
                    capturesCompleted++
                    if (capturesCompleted == count) {
                        // All frames captured, now process them
                        val bitmaps = capturedPaths.map { BitmapFactory.decodeFile(it) }
                        processor.stackFrames(bitmaps)
                        // In a real app, save resultBitmap and return its path
                        onSuccess(capturedPaths[0]) // Returning first for demo
                    }
                },
                onError = { error ->
                    onError(error)
                }
            )
        }
    }

    private fun createPhotoFile(): File {
        return File(
            context.externalCacheDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )
    }
}
