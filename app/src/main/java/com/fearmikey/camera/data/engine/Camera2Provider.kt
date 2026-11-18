package com.fearmikey.camera.data.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.gesture.GestureResult
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.processing.NightStacker
import com.fearmikey.camera.data.repository.MediaSaver
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.ImageSaveMode
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.UserPreferences
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Camera2 access for features CameraX cannot express:
 *  - Night: manual-exposure YUV burst merged by [NightStacker]
 *  - HDR: deterministic exposure bracket fused with OpenCV Mertens
 *  - RAW+JPEG stills (DNG via [DngCreator])
 *  - Slow motion via a constrained high-speed session + [MediaRecorder]
 *
 * The caller ([HybridCaptureEngine]) must release CameraX before calling into
 * this class and restore it afterwards. Callbacks may arrive on a background thread.
 */
class Camera2Provider(
    private val context: Context,
    private val processor: ImageProcessor,
    private val mediaSaver: MediaSaver,
    private val locationProvider: () -> Location?,
) : CaptureEngine {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentProConfig: ProConfig = ProConfig()
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentZoomRatio: Float = 1.0f
    private var targetRotation: Int = Surface.ROTATION_0
    private var exposureIndex: Int = 0

    // Slow motion recording state
    private var mediaRecorder: MediaRecorder? = null
    private var slowMoUri: Uri? = null
    private var slowMoPfd: ParcelFileDescriptor? = null

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2Background").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        backgroundThread = null
        backgroundHandler = null
        thread.quitSafely()
    }

    private fun handlerExecutor(): Executor =
        backgroundHandler?.let { h -> Executor { r -> h.post(r) } } ?: ContextCompat.getMainExecutor(context)

    private fun createSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        type: Int = SessionConfiguration.SESSION_REGULAR,
        onConfigured: (CameraCaptureSession) -> Unit,
        onFailed: () -> Unit,
    ) {
        val config = SessionConfiguration(
            type,
            surfaces.map { OutputConfiguration(it) },
            handlerExecutor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    onConfigured(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = onFailed()
            }
        )
        camera.createCaptureSession(config)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, onOpened: (CameraDevice) -> Unit, onError: (Exception) -> Unit) {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    onOpened(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice == camera) cameraDevice = null
                    onError(Exception("Camera open error: $error"))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector,
        meteringPointFactoryProvider: () -> androidx.camera.core.MeteringPointFactory?
    ) {
        // Camera2 opens the device on demand; just remember which lens to use.
        currentCameraSelector = cameraSelector
    }

    override fun setMode(mode: CameraMode) {
        currentMode = mode
    }

    override fun applySettings(prefs: UserPreferences) {
        currentPrefs = prefs
    }

    override fun getCapabilities(): CameraHardwareCapabilities? {
        val cameraId = findCameraId(currentCameraSelector) ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val isoRange = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val shutterSpeedRange =
            characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(1_000_000L, 1_000_000_000L)
        val minFocusDistance = characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0.0f
        val zoomRange = characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] ?: Range(1.0f, 10.0f)
        val isFront = characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT

        val presets = mutableListOf<Float>()
        if (zoomRange.lower < 0.95f) presets.add((Math.round(zoomRange.lower * 10) / 10.0).toFloat())
        presets.add(1.0f)
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
            zoomPresets = presets.distinct().sorted()
        )
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        startBackgroundThread()
        val cameraId = findCameraId(currentCameraSelector) ?: run {
            onError(Exception("No camera found"))
            return
        }
        // Guarantee exactly one completion callback and always release the device.
        val done = AtomicBoolean(false)
        val finishOk: (String) -> Unit = { uri ->
            if (done.compareAndSet(false, true)) {
                closeCamera()
                onSuccess(uri)
            }
        }
        val finishErr: (Exception) -> Unit = { e ->
            if (done.compareAndSet(false, true)) {
                closeCamera()
                onError(e)
            }
        }
        // Never leave the UI stuck in "processing" if a frame goes missing.
        backgroundHandler?.postDelayed({
            finishErr(java.util.concurrent.TimeoutException("Capture timed out"))
        }, CAPTURE_TIMEOUT_MS)
        when (currentMode) {
            CameraMode.NIGHT_SIGHT -> openCamera(cameraId, { captureStack(it, StackKind.NIGHT, finishOk, finishErr) }, finishErr)
            CameraMode.LONG_EXPOSURE -> openCamera(cameraId, { captureStack(it, StackKind.LONG_EXPOSURE, finishOk, finishErr) }, finishErr)
            CameraMode.HDR -> openCamera(cameraId, { captureHdr(it, finishOk, finishErr) }, finishErr)
            CameraMode.SLOW_MO, CameraMode.TIME_LAPSE, CameraMode.VIDEO ->
                onError(Exception("Use the record button in video modes."))
            else -> if (currentPrefs.hdrPhoto && currentPrefs.saveMode != ImageSaveMode.RAW_JPEG) {
                openCamera(cameraId, { captureHdr(it, finishOk, finishErr) }, finishErr)
            } else {
                openCamera(cameraId, { captureStill(it, finishOk, finishErr) }, finishErr)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private class Meter(val exposureNs: Long?, val iso: Int?, val focusDistance: Float?)

    private fun characteristics(camera: CameraDevice) = cameraManager.getCameraCharacteristics(camera.id)

    private fun supportsManualSensor(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun supportsRaw(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

    /** Largest output size for [format] whose area does not exceed [maxPixels]. */
    private fun pickSize(chars: CameraCharacteristics, format: Int, maxPixels: Long): Size {
        val sizes = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(format)
            ?: return Size(1920, 1080)
        return sizes.filter { it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }!!
    }

    /** Small offscreen target used to let AE/AF/AWB converge before a burst. */
    private fun createWarmupSurface(chars: CameraCharacteristics): Pair<SurfaceTexture, Surface> {
        val sizes = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?.getOutputSizes(SurfaceTexture::class.java)
        val size = sizes?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
        return texture to Surface(texture)
    }

    /** Clockwise rotation that makes the sensor output upright for the current device rotation. */
    private fun outputRotation(chars: CameraCharacteristics): Int {
        val sensor = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        val device = when (targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + device) % 360 else (sensor - device + 360) % 360
    }

    private fun applyCommon(builder: CaptureRequest.Builder) {
        builder[CaptureRequest.CONTROL_ZOOM_RATIO] = currentZoomRatio
        // Honour the user's brightness slider (metering during warm-up includes it).
        builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = exposureIndex
    }

    /**
     * Runs a repeating auto request on [warmup] until AE (and AF if active) settle
     * or [timeoutMs] passes, then reports the converged exposure parameters.
     */
    private fun warmUp(
        camera: CameraDevice,
        session: CameraCaptureSession,
        warmup: Surface,
        timeoutMs: Long = 1500,
        onReady: (Meter) -> Unit,
    ) {
        val start = System.currentTimeMillis()
        val fired = AtomicBoolean(false)
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(warmup)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyCommon(this)
        }
        var frames = 0
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result[CaptureResult.CONTROL_AE_STATE]
                val af = result[CaptureResult.CONTROL_AF_STATE]
                val aeDone = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                val afDone = af == null || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
                val timedOut = System.currentTimeMillis() - start > timeoutMs
                if ((frames >= 5 && aeDone && afDone) || timedOut) {
                    if (fired.compareAndSet(false, true)) {
                        try {
                            s.stopRepeating()
                        } catch (_: Exception) {
                        }
                        onReady(
                            Meter(
                                result[CaptureResult.SENSOR_EXPOSURE_TIME],
                                result[CaptureResult.SENSOR_SENSITIVITY],
                                result[CaptureResult.LENS_FOCUS_DISTANCE],
                            )
                        )
                    }
                }
            }
        }, backgroundHandler)
    }

    // ---------------------------------------------------------------------
    // Night mode
    // ---------------------------------------------------------------------

    /** Multi-frame stacks: Night lengthens exposure and lifts shadows; Long exposure keeps AE and blurs motion. */
    private enum class StackKind(val frames: Int, val prefix: String, val toneMap: Boolean, val lengthenExposure: Boolean) {
        NIGHT(NIGHT_FRAMES, "NIGHT", toneMap = true, lengthenExposure = true),
        LONG_EXPOSURE(LONG_EXPOSURE_FRAMES, "LONG", toneMap = false, lengthenExposure = false),
    }

    private fun captureStack(camera: CameraDevice, kind: StackKind, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val chars = characteristics(camera)
        val size = pickSize(chars, ImageFormat.YUV_420_888, MAX_NIGHT_PIXELS)
        val rotation = outputRotation(chars)
        val location = locationProvider()
        val stacker = processor.createNightStacker(size.width, size.height)
        var received = 0

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        val (texture, warmup) = createWarmupSurface(chars)
        val cleanup = {
            reader.close()
            warmup.release()
            texture.release()
            stacker.release()
        }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            stacker.addFrame(image) // closes the image
            received++
            if (received == kind.frames) {
                try {
                    val result = stacker.finish(rotation, kind.toneMap) ?: throw Exception("Merge produced no frames")
                    val uri = mediaSaver.saveJpeg(result, kind.prefix, location)
                    result.recycle()
                    if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save night photo"))
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    cleanup()
                }
            }
        }, backgroundHandler)

        createSession(camera, listOf(warmup, reader.surface), onConfigured = { session ->
            warmUp(camera, session, warmup) { meter ->
                try {
                    val manual = kind.lengthenExposure && supportsManualSensor(chars) &&
                        meter.exposureNs != null && meter.iso != null
                    val requests = List(kind.frames) {
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            applyCommon(this)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            if (manual) {
                                applyNightExposure(this, chars, meter)
                            } else {
                                set(CaptureRequest.CONTROL_AE_LOCK, true)
                            }
                        }.build()
                    }
                    session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                            onError(Exception("Night frame capture failed: ${f.reason}"))
                            cleanup()
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    onError(e)
                    cleanup()
                }
            }
        }, onFailed = {
            onError(Exception("Night session configuration failed"))
            cleanup()
        })
    }

    /** Trades ISO for a longer (still hand-holdable) exposure, slightly brighter than AE. */
    private fun applyNightExposure(builder: CaptureRequest.Builder, chars: CameraCharacteristics, meter: Meter) {
        val expRange = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(100_000L, 250_000_000L)
        val isoRange = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val e = meter.exposureNs!!
        val s = meter.iso!!
        val newExposure = (e * 2).coerceAtMost(NIGHT_MAX_EXPOSURE_NS).coerceIn(expRange.lower, expRange.upper)
        val newIso = (e.toDouble() * s * NIGHT_BRIGHTNESS_GAIN / newExposure).toInt()
            .coerceIn(isoRange.lower, isoRange.upper)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, newExposure)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, newIso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, newExposure)
        meter.focusDistance?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    // ---------------------------------------------------------------------
    // HDR
    // ---------------------------------------------------------------------

    private fun captureHdr(camera: CameraDevice, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val chars = characteristics(camera)
        val size = pickSize(chars, ImageFormat.JPEG, MAX_HDR_PIXELS)
        val rotation = outputRotation(chars)
        val location = locationProvider()
        val frames = mutableListOf<Bitmap>()

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, HDR_EV_STOPS.size)
        val (texture, warmup) = createWarmupSurface(chars)
        val cleanup = {
            reader.close()
            warmup.release()
            texture.release()
            frames.forEach { it.recycle() }
            frames.clear()
        }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val bitmap = image.use { decodeJpeg(it) }
            if (bitmap != null) frames.add(bitmap)
            if (frames.size == HDR_EV_STOPS.size) {
                try {
                    val fused = processor.fuseHdrFrames(frames) ?: throw Exception("HDR fusion failed")
                    val upright = rotate(fused, rotation)
                    val uri = mediaSaver.saveJpeg(upright, "HDR", location)
                    upright.recycle()
                    if (uri != null) onSuccess(uri.toString()) else onError(Exception("Failed to save HDR photo"))
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    cleanup()
                }
            }
        }, backgroundHandler)

        createSession(camera, listOf(warmup, reader.surface), onConfigured = { session ->
            warmUp(camera, session, warmup) { meter ->
                try {
                    val manual = supportsManualSensor(chars) && meter.exposureNs != null && meter.iso != null
                    val requests = HDR_EV_STOPS.map { ev ->
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            applyCommon(this)
                            if (manual) {
                                applyBracketExposure(this, chars, meter, ev)
                            } else {
                                val step = chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: (1f / 3f)
                                val range = chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] ?: Range(-6, 6)
                                set(
                                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                    Math.round(ev / step).coerceIn(range.lower, range.upper)
                                )
                            }
                        }.build()
                    }
                    session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                            onError(Exception("HDR frame capture failed: ${f.reason}"))
                            cleanup()
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    onError(e)
                    cleanup()
                }
            }
        }, onFailed = {
            onError(Exception("HDR session configuration failed"))
            cleanup()
        })
    }

    /** Sets a manual exposure [ev] stops away from the metered exposure. */
    private fun applyBracketExposure(builder: CaptureRequest.Builder, chars: CameraCharacteristics, meter: Meter, ev: Float) {
        val expRange = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(100_000L, 250_000_000L)
        val isoRange = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val target = meter.exposureNs!! * Math.pow(2.0, ev.toDouble())
        val exposure = target.toLong().coerceAtMost(HDR_MAX_EXPOSURE_NS).coerceIn(expRange.lower, expRange.upper)
        // Make up for clamped exposure time with gain.
        val iso = (meter.iso!! * (target / exposure)).toInt().coerceIn(isoRange.lower, isoRange.upper)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure)
        meter.focusDistance?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    // ---------------------------------------------------------------------
    // Still capture (RAW + JPEG / manual controls)
    // ---------------------------------------------------------------------

    private fun captureStill(camera: CameraDevice, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val chars = characteristics(camera)
        val jpegSize = pickSize(chars, ImageFormat.JPEG, Long.MAX_VALUE)
        val wantRaw = currentPrefs.saveMode == ImageSaveMode.RAW_JPEG && supportsRaw(chars)
        val rawSize = if (wantRaw) pickSize(chars, ImageFormat.RAW_SENSOR, Long.MAX_VALUE) else null
        val location = locationProvider()

        val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1)
        val rawReader = rawSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 1) }
        val (texture, warmup) = createWarmupSurface(chars)

        var jpegUri: String? = null
        var rawImage: Image? = null
        var rawResult: TotalCaptureResult? = null
        var rawDone = rawReader == null
        val cleanup = {
            jpegReader.close()
            rawReader?.close()
            warmup.release()
            texture.release()
        }
        // Runs on the background handler thread only.
        val maybeFinish = {
            val img = rawImage
            val res = rawResult
            if (!rawDone && img != null && res != null) {
                saveDng(chars, img, res, rotation = outputRotation(chars), location = location)
                rawDone = true
            }
            val uri = jpegUri
            if (rawDone && uri != null) {
                onSuccess(uri)
                cleanup()
            }
        }

        jpegReader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val bytes = image.use { ByteArray(it.planes[0].buffer.remaining()).also { b -> it.planes[0].buffer.get(b) } }
            val uri = saveJpegBytes(bytes)
            if (uri == null) {
                cleanup()
                onError(Exception("Failed to save photo"))
                return@setOnImageAvailableListener
            }
            jpegUri = uri.toString()
            maybeFinish()
        }, backgroundHandler)

        rawReader?.setOnImageAvailableListener({ r ->
            rawImage = r.acquireNextImage()
            maybeFinish()
        }, backgroundHandler)

        val surfaces = listOfNotNull(warmup, jpegReader.surface, rawReader?.surface)
        createSession(camera, surfaces, onConfigured = { session ->
            warmUp(camera, session, warmup) { _ ->
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(jpegReader.surface)
                        rawReader?.let { addTarget(it.surface) }
                        applyCommon(this)
                        set(CaptureRequest.JPEG_ORIENTATION, outputRotation(chars))
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                        location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
                        applyProConfig(this)
                    }.build()
                    session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                            rawResult = result
                            maybeFinish()
                        }

                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                            cleanup()
                            onError(Exception("Capture failed: ${failure.reason}"))
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    cleanup()
                    onError(e)
                }
            }
        }, onFailed = {
            cleanup()
            onError(Exception("Session configuration failed"))
        })
    }

    private fun saveJpegBytes(bytes: ByteArray): Uri? {
        val resolver = context.contentResolver
        val values = mediaSaver.newPhotoValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("No stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveDng(chars: CameraCharacteristics, image: Image, result: TotalCaptureResult, rotation: Int, location: Location?) {
        val resolver = context.contentResolver
        val values = mediaSaver.newPhotoValues("RAW").apply {
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        try {
            if (uri == null) return
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(
                    when (rotation) {
                        90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
                        180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
                        270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
                        else -> android.media.ExifInterface.ORIENTATION_NORMAL
                    }
                )
                location?.let { dng.setLocation(it) }
                resolver.openOutputStream(uri, "wt")?.use { dng.writeImage(it, image) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save DNG", e)
            uri?.let { resolver.delete(it, null, null) }
        } finally {
            image.close()
        }
    }

    private fun decodeJpeg(image: Image): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        })
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
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
            builder[CaptureRequest.CONTROL_AWB_MODE] = when {
                kelvin < 3300 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                kelvin < 4600 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                kelvin < 6000 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                else -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            }
        }
    }

    // ---------------------------------------------------------------------
    // Slow motion
    // ---------------------------------------------------------------------

    override fun startSlowMoVideo(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        startBackgroundThread()
        if (currentMode == CameraMode.TIME_LAPSE) {
            val id = findCameraId(currentCameraSelector) ?: run {
                onError(IllegalStateException("No camera found"))
                return
            }
            openCamera(id, { camera -> setupTimeLapseRecording(camera, onSuccess, onError) }, onError)
            return
        }
        val cameraId = findHighSpeedCamera()
        if (cameraId == null) {
            onError(IllegalStateException("This device does not support high-speed video"))
            return
        }
        openCamera(cameraId, { camera -> setupHighSpeedRecording(camera, onSuccess, onError) }, onError)
    }

    /**
     * Time lapse: a regular repeating session feeds MediaRecorder, which keeps one
     * frame every 1/[TIME_LAPSE_CAPTURE_FPS] s and encodes at 30 fps (15x speed-up).
     */
    private fun setupTimeLapseRecording(camera: CameraDevice, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        try {
            val chars = characteristics(camera)
            val configs = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val size = configs?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 && it.width * 9 == it.height * 16 }
                ?.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
            val fpsRange = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                ?.filter { it.upper == 30 }?.minByOrNull { it.lower } ?: Range(15, 30)

            val resolver = context.contentResolver
            val values = mediaSaver.newVideoValues("TIMELAPSE").apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create video entry")
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Unable to open video file")
            slowMoUri = uri
            slowMoPfd = pfd

            val recorder = MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(12_000_000)
                setCaptureRate(TIME_LAPSE_CAPTURE_FPS)
                setVideoFrameRate(SLOW_MO_PLAYBACK_FPS)
                setVideoSize(size.width, size.height)
                setOrientationHint(outputRotation(chars))
                locationProvider()?.let { setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }
            mediaRecorder = recorder
            val recorderSurface = recorder.surface

            createSession(camera, listOf(recorderSurface), onConfigured = { session ->
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                        applyCommon(this)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    }.build()
                    session.setRepeatingRequest(request, null, backgroundHandler)
                    recorder.start()
                    onSuccess()
                } catch (e: Exception) {
                    abortSlowMo()
                    onError(e)
                }
            }, onFailed = {
                abortSlowMo()
                onError(Exception("Time-lapse session configuration failed"))
            })
        } catch (e: Exception) {
            abortSlowMo()
            onError(e)
        }
    }

    private fun setupHighSpeedRecording(camera: CameraDevice, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        try {
            val chars = characteristics(camera)
            val configs = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val targetSize = configs?.highSpeedVideoSizes?.filter { it.width <= 1920 }?.maxByOrNull { it.width }
                ?: Size(1280, 720)
            val fpsRange = configs?.getHighSpeedVideoFpsRangesFor(targetSize)
                ?.filter { it.lower == it.upper }?.maxByOrNull { it.upper }
                ?: Range(120, 120)

            val resolver = context.contentResolver
            val values = mediaSaver.newVideoValues("SLOMO").apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create video entry")
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Unable to open video file")
            slowMoUri = uri
            slowMoPfd = pfd

            val recorder = MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(20_000_000)
                // Capture at high speed but encode at 30 fps, so the file itself
                // plays back in slow motion in any player.
                setCaptureRate(fpsRange.upper.toDouble())
                setVideoFrameRate(SLOW_MO_PLAYBACK_FPS)
                setVideoSize(targetSize.width, targetSize.height)
                setOrientationHint(outputRotation(chars))
                locationProvider()?.let { setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }
            mediaRecorder = recorder
            val recorderSurface = recorder.surface

            createSession(camera, listOf(recorderSurface), SessionConfiguration.SESSION_HIGH_SPEED, onConfigured = { session ->
                try {
                    val highSpeed = session as CameraConstrainedHighSpeedCaptureSession
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    }
                    highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(builder.build()), null, backgroundHandler)
                    recorder.start()
                    onSuccess()
                } catch (e: Exception) {
                    abortSlowMo()
                    onError(e)
                }
            }, onFailed = {
                abortSlowMo()
                onError(Exception("High-speed session configuration failed"))
            })
        } catch (e: Exception) {
            abortSlowMo()
            onError(e)
        }
    }

    private fun abortSlowMo() {
        mediaRecorder?.release()
        mediaRecorder = null
        slowMoPfd?.close()
        slowMoPfd = null
        slowMoUri?.let { context.contentResolver.delete(it, null, null) }
        slowMoUri = null
        closeCamera()
    }

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit) {
        val uri = slowMoUri
        var ok = true
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // Thrown when no frames were recorded.
            ok = false
        }
        mediaRecorder?.release()
        mediaRecorder = null
        slowMoPfd?.close()
        slowMoPfd = null
        slowMoUri = null
        closeCamera()

        if (uri == null) return
        if (ok) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            onSuccess(uri.toString())
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }

    override fun pauseVideo() {
        mediaRecorder?.pause()
    }

    override fun resumeVideo() {
        mediaRecorder?.resume()
    }

    override fun captureVideoSnapshot(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Video snapshot not supported during slow motion"))
    }

    private fun findCameraId(selector: CameraSelector): String? {
        val lensFacing = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == lensFacing
        }
    }

    private fun findHighSpeedCamera(): String? {
        val preferred = findCameraId(currentCameraSelector)
        val candidates = listOfNotNull(preferred) + cameraManager.cameraIdList.filter { it != preferred }
        return candidates.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
        }
    }

    // ---------------------------------------------------------------------
    // State setters (CameraX owns live preview controls)
    // ---------------------------------------------------------------------

    override fun setProMode(enabled: Boolean) {}

    override fun updateManualConfig(config: ProConfig) {
        currentProConfig = config
    }

    override fun setZoom(ratio: Float) {
        // Applied to Camera2 bursts so Night/HDR respect the selected zoom.
        currentZoomRatio = ratio
    }

    override fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit) {}
    override fun setFlashMode(flashMode: FlashMode) {}
    override fun setTorchEnabled(enabled: Boolean) {}
    override fun playShutterSound() {}
    override fun playTimerTickSound() {}
    override fun playStartRecordingSound() {}
    override fun playStopRecordingSound() {}
    override fun setGestureTriggerEnabled(enabled: Boolean) {}
    override fun setOnGestureListener(listener: (GestureResult) -> Unit) {}
    override fun setOnPanoramaProgressListener(listener: (com.fearmikey.camera.domain.model.PanoramaProgress) -> Unit) {}
    override fun setOnCameraReadyListener(listener: () -> Unit) {}

    override fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
    }

    override fun setExposureCompensation(index: Int) {
        exposureIndex = index
    }

    // Scene analysis runs on the CameraX preview, not here.
    override fun setOnLowLightListener(listener: (Boolean) -> Unit) {}
    override fun setOnQrCodeListener(listener: (String?) -> Unit) {}

    override fun startPanorama(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Panorama not supported in Camera2Provider"))
    }

    override fun stopPanorama(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Panorama not supported in Camera2Provider"))
    }

    override fun unbind() {
        if (mediaRecorder != null) abortSlowMo() else closeCamera()
        stopBackgroundThread()
    }

    private companion object {
        const val TAG = "Camera2Provider"
        const val NIGHT_FRAMES = 8
        const val LONG_EXPOSURE_FRAMES = 16
        const val TIME_LAPSE_CAPTURE_FPS = 2.0
        const val MAX_NIGHT_PIXELS = 12_600_000L
        const val MAX_HDR_PIXELS = 8_400_000L
        const val NIGHT_MAX_EXPOSURE_NS = 100_000_000L // 1/10 s: hand-holdable
        const val NIGHT_BRIGHTNESS_GAIN = 1.3
        const val HDR_MAX_EXPOSURE_NS = 250_000_000L
        const val CAPTURE_TIMEOUT_MS = 20_000L
        const val SLOW_MO_PLAYBACK_FPS = 30
        val HDR_EV_STOPS = listOf(-2f, 0f, 2f)
    }
}
