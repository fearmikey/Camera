package com.fearmikey.camera.data.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.domain.model.CameraMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.ImageReader
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
import com.fearmikey.camera.domain.model.VideoResolution as DomainVideoResolution
import com.fearmikey.camera.domain.model.VideoCodec as DomainVideoCodec
import java.io.FileOutputStream
import com.fearmikey.camera.data.engine.processing.StreamingFrameBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class Camera2Provider(
    private val context: Context,
    private val processor: ImageProcessor,
) : CaptureEngine {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    
    private var imageReader: ImageReader? = null
    private var streamingBuffer: StreamingFrameBuffer? = null
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private val burstSize = 15 // Increased for Night Sight
    private var captureCounter = AtomicInteger(0)

    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoPath: String? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val providerScope = CoroutineScope(Dispatchers.Default)

    private var currentMode: CameraMode = CameraMode.PHOTO
    private var previewSurface: Surface? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var currentProConfig: ProConfig = ProConfig()
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var rawImageReader: ImageReader? = null
    private var latestCaptureResult: TotalCaptureResult? = null
    private var currentZoomRatio: Float = 1.0f

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
    ) {
        val outputConfigs = surfaces.map { OutputConfiguration(it) }
        val executor = backgroundHandler?.let { h -> Executor { r -> h.post(r) } } 
            ?: ContextCompat.getMainExecutor(context)
            
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            executor,
            callback
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun createHighSpeedSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback
    ) {
        val outputConfigs = surfaces.map { OutputConfiguration(it) }
        val executor = backgroundHandler?.let { h -> Executor { r -> h.post(r) } } 
            ?: ContextCompat.getMainExecutor(context)
            
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            outputConfigs,
            executor,
            callback
        )
        camera.createCaptureSession(sessionConfig)
    }

    @SuppressLint("MissingPermission")
    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        this.lifecycleOwner = lifecycleOwner
        this.surfaceProvider = surfaceProvider
        this.currentCameraSelector = cameraSelector
        startBackgroundThread()
    }

    override fun setMode(mode: CameraMode) {
        val oldMode = currentMode
        currentMode = mode
        if ((oldMode != CameraMode.SLOW_MO) && (mode == CameraMode.SLOW_MO)) {
            previewSurface?.let { surface ->
                cameraDevice?.close()
                findHighSpeedCamera()?.let { openCameraForPreview(it, surface) }
            }
        } else if ((oldMode == CameraMode.SLOW_MO) && (mode != CameraMode.SLOW_MO)) {
            cameraDevice?.close()
            cameraDevice = null
            captureSession?.close()
            captureSession = null
        }
    }

    override fun applySettings(prefs: UserPreferences) {
        currentPrefs = prefs
        // If in slow-mo or pro mode, we might need to reconfigure
        if (currentMode == CameraMode.SLOW_MO) {
            // Re-setup media recorder or session if codec/resolution changed
            // Implementation pending
        }
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        val cameraId = findCameraId(currentCameraSelector) ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        val isoRange = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val shutterSpeedRange = characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(1_000_000L, 1_000_000_000L)
        val minFocusDistance = characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0.0f
        
        val zoomRange = characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] ?: Range(1.0f, 10.0f)
        val isFront = characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT

        // Determine presets within the supported range
        val presets = mutableListOf<Float>()
        
        // Add minimum zoom if it's less than 1.0 (typically wide angle)
        if (zoomRange.lower < 0.95f) {
            // Round to one decimal place for clean UI
            val roundedMin = (Math.round(zoomRange.lower * 10) / 10.0).toFloat()
            presets.add(roundedMin)
        }
        
        presets.add(1.0f)

        // For front camera, we usually only want wide and 1x
        // For back camera, we add telephoto presets
        if (!isFront) {
            if (zoomRange.upper >= 2.0f) presets.add(2.0f)
            if (zoomRange.upper >= 5.0f) presets.add(5.0f)
            if (zoomRange.upper >= 10.0f) presets.add(10.0f)
        }

        return CameraHardwareCapabilities(
            isoRange = isoRange,
            shutterSpeedRange = shutterSpeedRange,
            minFocusDistance = minFocusDistance,
            zoomRange = zoomRange,
            zoomPresets = presets.asSequence().distinct().sorted().toList()
        )
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        startBackgroundThread()
        val cameraId = findBackCamera() ?: run {
            onError(Exception("No back camera found"))
            return
        }

        when (currentMode) {
            CameraMode.NIGHT_SIGHT -> openCameraAndCaptureNightBurst(cameraId, onSuccess, onError)
            CameraMode.HDR -> openCameraAndCaptureHDRBurst(cameraId, onSuccess, onError)
            CameraMode.SLOW_MO -> onError(Exception("Capture photo not supported in Slow Mo mode."))
            else -> openCameraAndCapturePro(cameraId, onSuccess, onError)
        }
    }

    override fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Panorama not supported in Camera2Provider"))
    }

    override fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Panorama not supported in Camera2Provider"))
    }

    private fun findCameraId(selector: CameraSelector): String? {
        val lensFacing = when {
            selector == CameraSelector.DEFAULT_BACK_CAMERA -> CameraCharacteristics.LENS_FACING_BACK
            selector == CameraSelector.DEFAULT_FRONT_CAMERA -> CameraCharacteristics.LENS_FACING_FRONT
            else -> CameraCharacteristics.LENS_FACING_BACK
        }
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics[CameraCharacteristics.LENS_FACING] == lensFacing) return id
        }
        return null
    }

    private fun findBackCamera(): String? {
        return findCameraId(CameraSelector.DEFAULT_BACK_CAMERA)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndCaptureNightBurst(
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupNightBurstSession(camera, onSuccess, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                onError(Exception("Camera open error: $error"))
            }
        },
            backgroundHandler
        )
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndCapturePro(
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupProCaptureSession(camera, onSuccess, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                onError(Exception("Camera open error: $error"))
            }
        },
            backgroundHandler
        )
    }

    private fun setupProCaptureSession(
        camera: CameraDevice,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val characteristics = cameraManager.getCameraCharacteristics(camera.id)
        val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        
        val largestJpeg = configs?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        val largestRaw = configs?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        imageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2)
        rawImageReader = if (currentPrefs.saveMode == com.fearmikey.camera.domain.model.ImageSaveMode.RAW_JPEG) {
            ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 2)
        } else null

        val surfaces = mutableListOf(imageReader!!.surface)
        rawImageReader?.let { surfaces.add(it.surface) }
        previewSurface?.let { surfaces.add(it) }

        createCaptureSession(camera, surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureProPhoto(camera, session, onSuccess, onError)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(Exception("Session configuration failed"))
            }
        })
    }

    private fun captureProPhoto(
        camera: CameraDevice,
        session: CameraCaptureSession,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(imageReader!!.surface)
        rawImageReader?.let { builder.addTarget(it.surface) }
        
        applyProConfig(builder)

        // Callback for JPEG
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer[bytes]
            image.close()
            
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val path = saveBitmapToMediaStore(bitmap, "PRO")
            onSuccess(path)
            bitmap.recycle()
        }, backgroundHandler)

        // Callback for RAW
        rawImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val result = latestCaptureResult
            if (result != null) {
                saveRawImage(image, result)
            } else {
                image.close()
            }
        }, backgroundHandler)

        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                latestCaptureResult = result
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                onError(Exception("Capture failed: ${failure.reason}"))
            }
        }, backgroundHandler)
    }

    private fun saveRawImage(image: android.media.Image, result: TotalCaptureResult) {
        val cameraId = findBackCamera() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val dngCreator = DngCreator(characteristics, result)
        
        val file = File(
            context.externalCacheDir,
            "PRO_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".dng"
        )
        
        FileOutputStream(file).use { out ->
            dngCreator.writeImage(out, image)
        }
        image.close()
        dngCreator.close()
    }

    private fun setupNightBurstSession(
        camera: CameraDevice,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val characteristics = cameraManager.getCameraCharacteristics(camera.id)
        val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        
        // Use YUV_420_888 for high-quality raw-ish processing
        val largestSize = configs?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        streamingBuffer = processor.createNightSightBuffer(largestSize.width, largestSize.height, burstSize)

        imageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.YUV_420_888, burstSize).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                
                streamingBuffer?.addFrame(image) { count ->
                    if (count >= burstSize) {
                        // Processing done
                        providerScope.launch {
                            try {
                                val resultBitmap = streamingBuffer?.finalize()
                                if (resultBitmap != null) {
                                    val path = saveBitmapToMediaStore(resultBitmap, "NIGHT")
                                    onSuccess(path)
                                    resultBitmap.recycle()
                                }
                                streamingBuffer = null
                                
                                // Cleanup session
                                backgroundHandler?.post {
                                    captureSession?.close()
                                    captureSession = null
                                    cameraDevice?.close()
                                    cameraDevice = null
                                }
                            } catch (e: Exception) {
                                onError(e)
                            }
                        }
                    }
                }
            }, backgroundHandler)
        }

        val surfaces = mutableListOf(imageReader!!.surface)
        previewSurface?.let { surfaces.add(it) }

        createCaptureSession(camera, surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                
                val requests = mutableListOf<CaptureRequest>()
                for (i in 0 until burstSize) {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    builder.addTarget(imageReader!!.surface)
                    
                    // Night Sight specific AE: 
                    // We want longer exposures. We can use AE_EXPOSURE_COMPENSATION or manual shutter
                    builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON
                    builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = 2 // Boost exposure
                    
                    // Ensure high quality
                    builder[CaptureRequest.EDGE_MODE] = CaptureRequest.EDGE_MODE_HIGH_QUALITY
                    builder[CaptureRequest.NOISE_REDUCTION_MODE] = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    
                    requests.add(builder.build())
                }
                
                session.captureBurst(requests, null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(Exception("Session configuration failed"))
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndCaptureHDRBurst(
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupHDRBurstSession(camera, onSuccess, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                onError(Exception("Camera open error: $error"))
            }
        },
            backgroundHandler
        )
    }

    private fun setupHDRBurstSession(
        camera: CameraDevice,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val hdrBurstSize = 3
        val characteristics = cameraManager.getCameraCharacteristics(camera.id)
        val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val largestSize = configs?.getOutputSizes(ImageReader::class.java)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        imageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, hdrBurstSize).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                image.close()

                if (bitmap != null) {
                    capturedBitmaps.add(bitmap)
                    if (captureCounter.incrementAndGet() >= hdrBurstSize) {
                        // All frames captured, process them
                        val fusedBitmap = processor.fuseHDRFrames(capturedBitmaps)
                        val path = saveBitmapToMediaStore(fusedBitmap, "HDR")
                        capturedBitmaps.clear()
                        captureCounter.set(0)
                        
                        captureSession?.close()
                        cameraDevice?.close()
                        
                        onSuccess(path)
                    }
                }
            }, backgroundHandler)
        }

        val surfaces = listOf(imageReader!!.surface)
        createCaptureSession(camera, surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                
                val requests = mutableListOf<CaptureRequest>()
                val exposures = listOf(-2, 0, 2)
                
                for (ev in exposures) {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    builder.addTarget(imageReader!!.surface)
                    
                    builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON
                    builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = ev
                    
                    // Lock AF for consistency across the burst
                    builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    
                    requests.add(builder.build())
                }
                
                session.captureBurst(requests, null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(Exception("Session configuration failed"))
            }
        })
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, prefix: String): String {
        val name = "${prefix}_" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        
        val rotation = getRotationDegrees()
        val rotatedBitmap = if (rotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.ORIENTATION, rotation)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
            return it.toString()
        }
        return ""
    }

    private fun getRotationDegrees(): Int {
        val cameraId = findBackCamera() ?: return 0
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
    }

    override fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        startBackgroundThread()
        val cameraId = findHighSpeedCamera()
        if (cameraId == null) {
            onError(IllegalStateException("No high-speed camera found"))
            return
        }

        openCameraAndStartHighSpeedSession(cameraId, { onSuccess() }, onError)
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            captureSession?.close()
            cameraDevice?.close()
            
            currentVideoPath?.let { path ->
                val uri = saveToMediaStore(path)
                onSuccess(uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopBackgroundThread()
            backgroundHandler?.postDelayed({
                // Re-bind to restore preview after video
                val owner = lifecycleOwner
                val provider = surfaceProvider
                if (owner != null && provider != null) {
                    ContextCompat.getMainExecutor(context).execute {
                        bindToLifecycle(owner, provider, CameraSelector.DEFAULT_BACK_CAMERA)
                    }
                }
            }, 500)
        }
    }

    override fun pauseVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.pause()
        }
    }

    override fun resumeVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
        }
    }

    override fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        // Requires ImageReader in session. Not implemented for Camera2 provider yet.
        onError(UnsupportedOperationException("Video snapshot not supported in Camera2Provider"))
    }

    private fun findHighSpeedCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            if (capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true) {
                return id
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openCameraForPreview(cameraId: String, surface: Surface) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startHighSpeedPreview(camera, surface)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun startHighSpeedPreview(camera: CameraDevice, surface: Surface) {
        val characteristics = cameraManager.getCameraCharacteristics(camera.id)
        val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val targetSize = configs?.highSpeedVideoSizes?.firstOrNull { it.width <= 1920 } ?: Size(1280, 720)
        val targetFpsRange = configs?.getHighSpeedVideoFpsRangesFor(targetSize)?.maxByOrNull { it.upper } ?: Range(120, 120)

        createHighSpeedSession(camera, listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder.addTarget(surface)
                builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = targetFpsRange
                
                val requestList = highSpeedSession.createHighSpeedRequestList(builder.build())
                highSpeedSession.setRepeatingBurst(requestList, null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        })
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndStartHighSpeedSession(
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupMediaRecorderAndSession(camera, cameraId, onSuccess, onError)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                onError(Exception("Camera open error: $error"))
            }
        },
            backgroundHandler
        )
    }

    private fun setupMediaRecorderAndSession(
        camera: CameraDevice,
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val targetSize = configs?.highSpeedVideoSizes?.firstOrNull { it.width <= 1920 } ?: Size(1280, 720)
        val targetFpsRange = configs?.getHighSpeedVideoFpsRangesFor(targetSize)?.maxByOrNull { it.upper } ?: Range(120, 120)

        val videoFile = createVideoFile()
        currentVideoPath = videoFile.absolutePath

        mediaRecorder = MediaRecorder(context).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(20_000_000)
            setVideoFrameRate(targetFpsRange.upper)
            setVideoSize(targetSize.width, targetSize.height)
            setOutputFile(videoFile.absolutePath)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface
        val surfaces = mutableListOf(recorderSurface)
        previewSurface?.let { surfaces.add(it) }
        
        createHighSpeedSession(camera, surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(recorderSurface)
                previewSurface?.let { builder.addTarget(it) }
                builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = targetFpsRange
                
                val requestList = highSpeedSession.createHighSpeedRequestList(builder.build())
                highSpeedSession.setRepeatingBurst(requestList, null, backgroundHandler)
                
                mediaRecorder?.start()
                onSuccess(videoFile.absolutePath)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(Exception("Session configuration failed"))
            }
        })
    }

    private fun applyProConfig(builder: CaptureRequest.Builder) {
        currentProConfig.iso?.let {
            builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_OFF
            builder[CaptureRequest.SENSOR_SENSITIVITY] = it
        }
        currentProConfig.shutterSpeedNs?.let {
            builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_OFF
            builder[CaptureRequest.SENSOR_EXPOSURE_TIME] = it
        }
        currentProConfig.focusDistance?.let {
            builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_OFF
            builder[CaptureRequest.LENS_FOCUS_DISTANCE] = it
        }
        currentProConfig.whiteBalanceKelvin?.let { kelvin ->
            builder[CaptureRequest.CONTROL_AWB_MODE] = CaptureRequest.CONTROL_AWB_MODE_OFF
            // Approximate Kelvin to color gains (simplified for demo)
            val gains = kelvinToColorGains(kelvin)
            builder[CaptureRequest.COLOR_CORRECTION_GAINS] = gains
        }
    }

    private fun kelvinToColorGains(kelvin: Int): RggbChannelVector {
        // Simplified mapping for demo purposes
        val r = if (kelvin < 5000) 2.0f else 1.0f
        val g = 1.0f
        val b = if (kelvin > 5000) 2.0f else 1.0f
        return RggbChannelVector(r, g, g, b)
    }

    override fun setProMode(enabled: Boolean) {
        if (enabled) {
            startProPreview()
        } else {
            stopProPreview()
        }
    }

    override fun setZoom(ratio: Float) {
        currentZoomRatio = ratio
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = camera.createCaptureRequest(
                if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO)
                    CameraDevice.TEMPLATE_RECORD
                else
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewSurface?.let { builder.addTarget(it) }
            
            builder[CaptureRequest.CONTROL_ZOOM_RATIO] = ratio

            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun focusAt(x: Float, y: Float) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val sensorRect = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: Rect(0, 0, 1920, 1080)
            
            // Map 0..1 coordinates to sensor coordinates
            val centerX = (x * sensorRect.width()).toInt()
            val centerY = (y * sensorRect.height()).toInt()
            val regionSize = 100
            val rect = Rect(
                (centerX - regionSize).coerceIn(0, sensorRect.width()),
                (centerY - regionSize).coerceIn(0, sensorRect.height()),
                (centerX + regionSize).coerceIn(0, sensorRect.width()),
                (centerY + regionSize).coerceIn(0, sensorRect.height())
            )
            
            builder[CaptureRequest.CONTROL_AF_REGIONS] = arrayOf(android.hardware.camera2.params.MeteringRectangle(rect, 1000))
            builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_AUTO
            builder[CaptureRequest.CONTROL_AF_TRIGGER] = CaptureRequest.CONTROL_AF_TRIGGER_START
            
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setFlashMode(flashMode: FlashMode) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = camera.createCaptureRequest(
                if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO)
                    CameraDevice.TEMPLATE_RECORD
                else
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewSurface?.let { builder.addTarget(it) }
            
            applyFlashToBuilder(builder, flashMode)
            
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setTorchEnabled(enabled: Boolean) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = camera.createCaptureRequest(
                if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO)
                    CameraDevice.TEMPLATE_RECORD
                else
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewSurface?.let { builder.addTarget(it) }
            
            builder[CaptureRequest.FLASH_MODE] = if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun playShutterSound() {}
    override fun playTimerTickSound() {}
    override fun playStartRecordingSound() {}
    override fun playStopRecordingSound() {}

    override fun setGestureTriggerEnabled(enabled: Boolean) {
        // Hand Gesture trigger not implemented for Camera2 provider yet.
    }

    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {
        // Hand Gesture trigger not implemented for Camera2 provider yet.
    }

    override fun setOnPanoramaListener(listener: (String) -> Unit) {
        // Panorama listener not implemented for Camera2 provider yet.
    }

    private fun applyFlashToBuilder(builder: CaptureRequest.Builder, flashMode: FlashMode) {
        when (flashMode) {
            FlashMode.OFF -> {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON
                builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_OFF
            }
            FlashMode.ON -> {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_OFF // Controlled by AE
            }
            FlashMode.AUTO -> {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_OFF // Controlled by AE
            }
        }
    }

    private fun startProPreview() {
        // Simplified surface handling
        // In a real app, we would use provider.onSurfaceRequested(...)
        // But for this demo, we'll assume previewSurface is already set or handled elsewhere
        // Actually, let's just make it work with the existing session if it exists
        updateManualConfig(currentProConfig)
    }

    private fun stopProPreview() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    override fun updateManualConfig(config: ProConfig) {
        currentProConfig = config
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            applyProConfig(builder)
            
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    latestCaptureResult = result
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun saveToMediaStore(path: String): String {
        val file = File(path)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camera")
        }

        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            file.delete()
            return it.toString()
        }
        return ""
    }

    private fun createVideoFile(): File {
        return File(
            context.externalCacheDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"
        )
    }

    override fun unbind() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }
}
