package com.fearmikey.camera.data.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
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
import com.fearmikey.camera.domain.model.CapturePipeline
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.FrameGeometry
import com.fearmikey.camera.domain.model.ImageSaveMode
import com.fearmikey.camera.domain.model.LightMode
import com.fearmikey.camera.domain.model.ModeCapabilities
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.UserPreferences
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    /** Manual controls panel is on; [currentProConfig] only applies while it is. */
    @Volatile private var isProMode = false
    private var currentPrefs: UserPreferences = UserPreferences()
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentZoomRatio: Float = 1.0f
    private var targetRotation: Int = Surface.ROTATION_0
    private var exposureIndex: Int = 0
    /** Visible viewfinder width / height (setViewfinderAspectRatio); null until measured. */
    @Volatile private var viewfinderWOverH: Float? = null
    /** Last tap (normalized viewfinder position) until cancelFocus or a camera switch. */
    @Volatile private var focusPoint: Pair<Float, Float>? = null

    @Volatile private var progressListener: ((Float) -> Unit)? = null

    private fun reportProgress(progress: Float) {
        progressListener?.invoke(progress.coerceIn(0f, 1f))
    }

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
        meteringPointProvider: MeteringPointProvider
    ) {
        // Camera2 opens the device on demand; just remember which lens to use. A new lens
        // starts at 1x (like CameraX); the ViewModel then sends the mode's starting zoom.
        if (cameraSelector != currentCameraSelector) {
            currentZoomRatio = 1f
            focusPoint = null
        }
        currentCameraSelector = cameraSelector
    }

    private val isFrontCamera: Boolean
        get() = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

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

        val labelScale = ZoomPresets.labelScale(cameraManager, cameraId, isFront)
        return CameraHardwareCapabilities(
            isoRange = isoRange,
            shutterSpeedRange = shutterSpeedRange,
            minFocusDistance = minFocusDistance,
            zoomRange = zoomRange,
            zoomPresets = ZoomPresets.compute(cameraManager, cameraId, zoomRange.lower, zoomRange.upper, isFront, labelScale),
            zoomLabelScale = labelScale,
        )
    }

    /**
     * Standalone entry point (HybridCaptureEngine calls [capture] with the pipeline it routed,
     * including Auto Night). Routes with [ModeCapabilities.pipelineFor] like the router does.
     */
    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val pipeline = ModeCapabilities.pipelineFor(
            currentMode, isFrontCamera, currentPrefs,
            manualExposure = isProMode && currentProConfig.hasManualExposure,
        )
        capture(if (pipeline.usesCamera2) pipeline else CapturePipeline.RAW_STILL, onSuccess, onError)
    }

    /** Runs one Camera2 still [pipeline] (Night, Long exposure, HDR or RAW + JPEG still). */
    fun capture(pipeline: CapturePipeline, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        if (pipeline in setOf(CapturePipeline.VIDEO, CapturePipeline.SLOW_MO, CapturePipeline.TIME_LAPSE)) {
            onError(Exception("Use the record button in video modes."))
            return
        }
        runCapture(onSuccess, onError) { camera, ok, err ->
            when (pipeline) {
                CapturePipeline.NIGHT -> captureStack(camera, StackKind.NIGHT, ok, err)
                CapturePipeline.LONG_EXPOSURE -> captureStack(camera, StackKind.LONG_EXPOSURE, ok, err)
                CapturePipeline.HDR -> captureHdr(camera, ok, err)
                else -> captureStill(camera, ok, err)
            }
        }
    }

    /** Opens the current lens and runs [block] with single-shot completion callbacks and a timeout. */
    private fun runCapture(
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit,
        block: (CameraDevice, (String) -> Unit, (Exception) -> Unit) -> Unit,
    ) {
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
        openCamera(cameraId, { block(it, finishOk, finishErr) }, finishErr)
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /**
     * Converged auto-exposure. [boost] is CONTROL_POST_RAW_SENSITIVITY_BOOST (100 = none): in
     * dark scenes AE adds digital gain after RAW on many devices (e.g. Pixels). It must be
     * carried over into manual requests, otherwise the shot is darker than the preview.
     */
    private class Meter(val exposureNs: Long?, val iso: Int?, val boost: Int?, val focusDistance: Float?)

    private fun characteristics(camera: CameraDevice) = cameraManager.getCameraCharacteristics(camera.id)

    private fun supportsManualSensor(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun supportsRaw(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

    /** What the viewfinder shows (long/short); photos are cropped to it. Null = no crop. */
    private fun photoAspect(): Float? = FrameGeometry.photoAspect(currentPrefs.aspectRatio, viewfinderWOverH)

    /**
     * Output size for [format] of at most [maxPixels] matching the viewfinder ([aspect]), see
     * [FrameGeometry.pickSize]. [preferExact]: a size of exactly that shape if there is one
     * (the camera's JPEG is then saved without re-encoding).
     */
    private fun pickSize(
        chars: CameraCharacteristics,
        format: Int,
        maxPixels: Long,
        aspect: Float? = photoAspect(),
        preferExact: Boolean = false,
    ): Size {
        val sizes = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(format)
            ?.map { it.width to it.height }
        val picked = sizes?.let { FrameGeometry.pickSize(it, maxPixels, aspect, preferExact) }
            ?: return Size(1920, 1080)
        return Size(picked.first, picked.second)
    }

    /**
     * Makes an upright [bitmap] match the viewfinder: center crop to [photoAspect] and, for
     * front-camera selfies with "Keep selfies as seen", the preview's mirror image. Recycles
     * [bitmap] when a new one is made.
     */
    private fun finishBitmap(bitmap: Bitmap, chars: CameraCharacteristics): Bitmap {
        val box = photoAspect()?.let { FrameGeometry.centerCrop(bitmap.width, bitmap.height, it) }
            ?: FrameGeometry.Box(0, 0, bitmap.width, bitmap.height)
        val mirror = currentPrefs.mirrorSelfies && isFront(chars)
        if (!mirror && box.width == bitmap.width && box.height == bitmap.height) return bitmap
        val matrix = Matrix().apply { if (mirror) postScale(-1f, 1f) }
        return Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun isFront(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT

    /** Tap-to-focus for a Camera2 capture: [region] in active-array coordinates. */
    private class TapMetering(val region: MeteringRectangle, val focus: Boolean)

    /**
     * The last tap ([focusPoint]) as a metering region, mapped from the viewfinder to the
     * sensor like CameraX's tap-to-focus. 3A regions are relative to the zoomed field of view
     * (CONTROL_ZOOM_RATIO), so zoom needs no correction. Null without a tap or region support.
     */
    private fun tapMetering(chars: CameraCharacteristics): TapMetering? {
        val (x, y) = focusPoint ?: return null
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
        val maxAf = chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0
        val maxAe = chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0
        val focus = maxAf > 0 && chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]
            ?.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) == true
        if (!focus && maxAe <= 0) return null
        val (ax, ay) = FrameGeometry.viewfinderToSensor(
            x, y, viewfinderWOverH,
            streamAspect = FrameGeometry.previewStreamAspect(currentPrefs.aspectRatio),
            activeAspect = FrameGeometry.longOverShort(active.width(), active.height()),
            sensorOrientation = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
            isFront = isFront(chars),
        )
        val w = (active.width() * TAP_REGION_SIZE).roundToInt().coerceAtLeast(1)
        val h = (active.height() * TAP_REGION_SIZE).roundToInt().coerceAtLeast(1)
        val left = ((ax * active.width()).roundToInt() - w / 2).coerceIn(0, active.width() - w)
        val top = ((ay * active.height()).roundToInt() - h / 2).coerceIn(0, active.height() - h)
        return TapMetering(MeteringRectangle(left, top, w, h, MeteringRectangle.METERING_WEIGHT_MAX), focus)
    }

    /** AF (AUTO mode, no trigger: keeps a locked lens), AE and AWB regions where supported. */
    private fun applyTapMetering(builder: CaptureRequest.Builder, chars: CameraCharacteristics, tap: TapMetering) {
        val regions = arrayOf(tap.region)
        if (tap.focus) {
            builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_AUTO
            builder[CaptureRequest.CONTROL_AF_REGIONS] = regions
        }
        if ((chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0) {
            builder[CaptureRequest.CONTROL_AE_REGIONS] = regions
        }
        if ((chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AWB] ?: 0) > 0) {
            builder[CaptureRequest.CONTROL_AWB_REGIONS] = regions
        }
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
        // Honor the user's brightness slider (metering during warm-up includes it).
        builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = exposureIndex
    }

    /**
     * Runs a repeating auto request on [warmup] until AE (and AF if active) settle
     * or [timeoutMs] passes, then reports the converged exposure parameters.
     * [longExposureMetering] lets AE use the slowest frame rate the camera offers, so in the
     * dark it meters with a long exposure instead of maxing out gain at 1/30 s.
     * After a tap ([focusPoint]) AE/AWB meter the tapped spot and AF runs one AUTO scan there
     * (trigger), waiting for the lens to lock; the burst then keeps that focus.
     */
    private fun warmUp(
        camera: CameraDevice,
        session: CameraCaptureSession,
        warmup: Surface,
        timeoutMs: Long = 1500,
        longExposureMetering: Boolean = false,
        onReady: (Meter) -> Unit,
    ) {
        val start = System.currentTimeMillis()
        val fired = AtomicBoolean(false)
        val chars = characteristics(camera)
        val tap = tapMetering(chars)
        val tapFocus = tap?.focus == true
        // An AF scan takes longer than continuous AF settling, especially at low frame rates.
        val timeout = if (tapFocus) timeoutMs + TAP_FOCUS_EXTRA_MS else timeoutMs
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(warmup)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (longExposureMetering) {
                chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                    ?.minWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
                    ?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            applyCommon(this)
            tap?.let { applyTapMetering(this, chars, it) }
        }
        var frames = 0
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result[CaptureResult.CONTROL_AE_STATE]
                val af = result[CaptureResult.CONTROL_AF_STATE]
                val aeDone = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                val locked = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                val afDone = af == null || locked || (!tapFocus && (
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                        af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
                val timedOut = System.currentTimeMillis() - start > timeout
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
                                result[CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST],
                                result[CaptureResult.LENS_FOCUS_DISTANCE],
                            )
                        )
                    }
                }
            }
        }, backgroundHandler)
        if (tapFocus) {
            // One AF scan on the tapped region; the repeating request (AUTO, no trigger) then
            // holds the lens where it locked.
            try {
                builder[CaptureRequest.CONTROL_AF_TRIGGER] = CaptureRequest.CONTROL_AF_TRIGGER_START
                session.capture(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "AF trigger failed", e)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Night mode
    // ---------------------------------------------------------------------

    /**
     * Multi-frame stacks. Night: long hand-holdable frames, merged and brightened in linear
     * light with a highlight roll-off. Long exposure: back-to-back frames at base ISO, averaged
     * in linear light for motion blur, with light-trail recovery.
     */
    private enum class StackKind(val label: String, val prefix: String, val fallbackFrames: Int) {
        NIGHT("Night", "NIGHT", NIGHT_FRAMES_MIN),
        LONG_EXPOSURE("Long exposure", "LONG", LONG_EXPOSURE_FRAMES_MIN),
    }

    /**
     * Manual exposure for every frame of a stack. [frameDurationNs] >= [exposureNs]; equal
     * means no gap between frames. [boost] is null when the camera has no post-RAW boost
     * control; [digitalGain] is the brightness the sensor settings could not reach, applied (in
     * linear light) when the stack is merged.
     */
    private data class StackPlan(
        val exposureNs: Long,
        val frameDurationNs: Long,
        val iso: Int,
        val boost: Int?,
        val digitalGain: Float,
        val frames: Int,
    )

    /** Splits an ISO-equivalent [sensitivity] into sensor ISO, post-RAW boost and merge gain. */
    private data class SensorGain(val iso: Int, val boost: Int?, val digitalGain: Float)

    private fun splitSensitivity(chars: CameraCharacteristics, sensitivity: Double): SensorGain {
        val isoRange = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val boostRange = chars[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]
        val iso = sensitivity.toInt().coerceIn(isoRange.lower, isoRange.upper)
        var remaining = sensitivity / iso
        val boost = boostRange?.let { r -> (remaining * 100).roundToInt().coerceIn(r.lower, r.upper) }
        if (boost != null) remaining /= boost / 100.0
        return SensorGain(iso, boost, remaining.coerceIn(1.0, MAX_PLAN_GAIN).toFloat())
    }

    /** Metered exposure "amount" in ns x ISO, with the ISP's post-RAW gain folded in. */
    private fun meteredAmount(meter: Meter): Double =
        meter.exposureNs!!.toDouble() * meter.iso!! * (meter.boost ?: 100).coerceAtLeast(100) / 100.0

    /**
     * Longest hand-holdable time per frame. Without OIS every frame is smeared by hand shake,
     * and alignment can't undo blur within a frame. Shorter when zoomed in.
     */
    private fun handheldMaxExposureNs(chars: CameraCharacteristics): Long {
        val base = if (hasOis(chars)) HANDHELD_MAX_EXPOSURE_NS else HANDHELD_MAX_EXPOSURE_NO_OIS_NS
        return (base / currentZoomRatio.coerceAtLeast(1f)).toLong().coerceAtLeast(HANDHELD_MIN_EXPOSURE_NS)
    }

    /**
     * Keeps (slightly more than) the metered brightness, including AE's post-RAW boost, but
     * moves it from gain into exposure time: up to 4x the metered time, capped at the
     * hand-holdable limit. Darker scenes get more frames, since averaging N frames cuts noise
     * by sqrt(N).
     */
    private fun planNight(chars: CameraCharacteristics, meter: Meter): StackPlan {
        val expRange = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(100_000L, 250_000_000L)
        val isoRange = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val metered = meteredAmount(meter)
        val target = metered * NIGHT_CAPTURE_GAIN

        var exposure = (meter.exposureNs!! * NIGHT_EXPOSURE_STRETCH).coerceAtMost(handheldMaxExposureNs(chars))
        // Bright scene: don't go below the base ISO, shorten the exposure instead.
        if (target / exposure < isoRange.lower.toDouble()) exposure = (target / isoRange.lower).toLong()
        exposure = exposure.coerceIn(expRange.lower, expRange.upper)
        val gain = splitSensitivity(chars, target / exposure)

        val isoSeconds = metered / 1e9
        val frames = when {
            isoSeconds >= 150 -> NIGHT_FRAMES_MAX
            isoSeconds >= 50 -> (NIGHT_FRAMES_MIN + NIGHT_FRAMES_MAX) / 2
            else -> NIGHT_FRAMES_MIN
        }
        return StackPlan(exposure, exposure, gain.iso, gain.boost, gain.digitalGain, frames)
    }

    /**
     * Long exposure: keeps the metered brightness (incl. post-RAW boost and the user's exposure
     * compensation) but puts as much of it as possible into time at the base ISO, so each frame
     * is as long as hand-holding allows (motion smears within a frame instead of leaving
     * stepped copies between frames, and noise is lowest). Frames run back to back (frame
     * duration = exposure, or the stream's minimum when the exposure is shorter) and cover
     * about [LONG_EXPOSURE_TARGET_NS]; more frames also average out the residual noise.
     */
    private fun planLongExposure(chars: CameraCharacteristics, meter: Meter, size: Size): StackPlan {
        val expRange = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] ?: Range(100_000L, 250_000_000L)
        val isoRange = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] ?: Range(100, 3200)
        val target = meteredAmount(meter)
        val exposure = (target / isoRange.lower).toLong()
            .coerceAtMost(handheldMaxExposureNs(chars))
            .coerceIn(expRange.lower, expRange.upper)
        val gain = splitSensitivity(chars, target / exposure)

        val minFrameNs = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            ?.takeIf { it > 0 } ?: DEFAULT_FRAME_DURATION_NS
        val frameNs = maxOf(exposure, minFrameNs)
        val frames = ((LONG_EXPOSURE_TARGET_NS + frameNs - 1) / frameNs).toInt()
            .coerceIn(LONG_EXPOSURE_FRAMES_MIN, LONG_EXPOSURE_FRAMES_MAX)
        return StackPlan(exposure, frameNs, gain.iso, gain.boost, gain.digitalGain, frames)
    }

    private fun hasOis(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
            ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

    /**
     * Noise reduction for stacked frames: the lightest mode available. Averaging already
     * removes most noise, and HIGH_QUALITY smooths each frame into identical flat 8-bit steps,
     * leaving nothing for the average to dither (the merge shows them as contour bands),
     * smears fine texture, and may lower the frame rate (gaps in a long exposure).
     */
    private fun stackNoiseReduction(chars: CameraCharacteristics): Int {
        val modes = chars[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]
            ?: return CaptureRequest.NOISE_REDUCTION_MODE_FAST
        return listOf(
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
        ).firstOrNull { it in modes } ?: CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
    }

    private fun captureStack(camera: CameraDevice, kind: StackKind, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val chars = characteristics(camera)
        val size = pickSize(chars, ImageFormat.YUV_420_888, MAX_NIGHT_PIXELS)
        val rotation = outputRotation(chars)
        val location = locationProvider()
        val stacker = processor.createNightStacker(size.width, size.height, longExposure = kind == StackKind.LONG_EXPOSURE)
        // All of this state is touched only on the background handler thread (warm-up, burst
        // and reader callbacks share it). [expected] is set when the burst is planned; frames
        // only arrive afterwards.
        var received = 0
        var dropped = 0
        var expected = kind.fallbackFrames
        var digitalGain = 1f
        var firstTimestamp = 0L
        var lastTimestamp = 0L
        var done = false

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        val (texture, warmup) = createWarmupSurface(chars)
        val cleanup = {
            reader.close()
            warmup.release()
            texture.release()
            stacker.release()
        }
        val fail = { e: Exception ->
            if (!done) {
                done = true
                onError(e)
                cleanup()
            }
        }

        // Merges once every planned frame has arrived or been lost. A lost frame (buffer error,
        // failed request) costs a little noise/smoothness instead of the whole shot.
        val maybeFinish = finish@{
            if (done || received + dropped < expected) return@finish
            done = true
            try {
                Log.i(
                    TAG, "${kind.label}: ${stacker.frameCount} merged, $dropped lost, " +
                        "burst span ${(lastTimestamp - firstTimestamp) / 1_000_000} ms"
                )
                val result = stacker.finish(rotation, digitalGain)
                    ?.let { finishBitmap(it, chars) }
                    ?: throw Exception("Merge produced no frames")
                reportProgress(PROGRESS_MERGED)
                val uri = mediaSaver.saveJpeg(result, kind.prefix, location)
                result.recycle()
                if (uri != null) {
                    reportProgress(1f)
                    onSuccess(uri.toString())
                } else {
                    onError(Exception("Failed to save ${kind.label.lowercase()} photo"))
                }
            } catch (e: Exception) {
                onError(e)
            } finally {
                cleanup()
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            if (done) {
                image.close()
                return@setOnImageAvailableListener
            }
            stacker.addFrame(image) // closes the image
            received++
            reportProgress(PROGRESS_METERED + (STACK_PROGRESS_FRAMES_END - PROGRESS_METERED) * received / expected)
            maybeFinish()
        }, backgroundHandler)

        // runCapture's timeout closes the camera, after which no more frames arrive: free the
        // native accumulator (hundreds of MB at 12 MP) instead of leaking it. Posted after
        // runCapture's own timeout, so it runs second; a merge in progress blocks this thread.
        backgroundHandler?.postDelayed({ fail(java.util.concurrent.TimeoutException("Capture timed out")) }, CAPTURE_TIMEOUT_MS)

        createSession(camera, listOf(warmup, reader.surface), onConfigured = { session ->
            warmUp(camera, session, warmup, longExposureMetering = true) { meter ->
                reportProgress(PROGRESS_METERED)
                try {
                    val manual = supportsManualSensor(chars) && meter.exposureNs != null && meter.iso != null
                    val plan = when {
                        !manual -> null
                        kind == StackKind.LONG_EXPOSURE -> planLongExposure(chars, meter, size)
                        else -> planNight(chars, meter)
                    }
                    if (plan != null) {
                        expected = plan.frames
                        digitalGain = plan.digitalGain
                        Log.i(
                            TAG, "${kind.label}: metered ${meter.exposureNs}ns ISO ${meter.iso} boost ${meter.boost} -> " +
                                "${plan.frames} x ${plan.exposureNs}ns (frame ${plan.frameDurationNs}ns) ISO ${plan.iso} " +
                                "boost ${plan.boost} gain ${plan.digitalGain}"
                        )
                    }
                    val requests = List(expected) {
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            applyCommon(this)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, stackNoiseReduction(chars))
                            // Sharpening is done once, after the merge.
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            if (hasOis(chars)) {
                                set(
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                )
                            }
                            // Same white balance for every frame of the stack.
                            set(CaptureRequest.CONTROL_AWB_LOCK, true)
                            if (plan != null) {
                                applyStackExposure(this, plan, meter)
                            } else {
                                set(CaptureRequest.CONTROL_AE_LOCK, true)
                            }
                        }.build()
                    }
                    session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                            val ts = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
                            if (firstTimestamp == 0L) firstTimestamp = ts
                            lastTimestamp = ts
                        }

                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                            Log.w(TAG, "${kind.label} frame failed: ${f.reason} (image captured: ${f.wasImageCaptured()})")
                            // A captured image still arrives at the reader; otherwise count it lost.
                            if (!f.wasImageCaptured()) {
                                dropped++
                                maybeFinish()
                            }
                        }

                        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                            Log.w(TAG, "${kind.label} frame $frameNumber: buffer lost")
                            dropped++
                            maybeFinish()
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }, onFailed = {
            fail(Exception("${kind.label} session configuration failed"))
        })
    }

    /** Applies [plan] (AE off). The post-RAW boost is always set explicitly when supported. */
    private fun applyStackExposure(builder: CaptureRequest.Builder, plan: StackPlan, meter: Meter) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, plan.frameDurationNs)
        plan.boost?.let { builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) }
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
            // Fusion is the slow part of HDR, so frames only fill part of the ring.
            reportProgress(PROGRESS_METERED + (HDR_PROGRESS_FRAMES_END - PROGRESS_METERED) * frames.size / HDR_EV_STOPS.size)
            if (frames.size == HDR_EV_STOPS.size) {
                try {
                    val fused = processor.fuseHdrFrames(frames) ?: throw Exception("HDR fusion failed")
                    reportProgress(PROGRESS_MERGED)
                    val upright = finishBitmap(rotate(fused, rotation), chars)
                    val uri = mediaSaver.saveJpeg(upright, "HDR", location)
                    upright.recycle()
                    if (uri != null) {
                        reportProgress(1f)
                        onSuccess(uri.toString())
                    } else {
                        onError(Exception("Failed to save HDR photo"))
                    }
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    cleanup()
                }
            }
        }, backgroundHandler)

        createSession(camera, listOf(warmup, reader.surface), onConfigured = { session ->
            warmUp(camera, session, warmup) { meter ->
                reportProgress(PROGRESS_METERED)
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
                                // Bracket around the user's brightness setting, not around 0.
                                set(
                                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                    (exposureIndex + Math.round(ev / step)).coerceIn(range.lower, range.upper)
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
        // Keep AE's post-RAW boost, or the 0 EV frame comes out darker than the metered exposure.
        if (chars[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE] != null) {
            meter.boost?.let { builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) }
        }
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
        // The camera's own JPEG is saved as is when it already has the viewfinder's shape;
        // otherwise (e.g. the full-screen ratio) it is cropped in finishJpeg. The DNG keeps the
        // whole sensor: RAW editors crop.
        val jpegSize = pickSize(chars, ImageFormat.JPEG, Long.MAX_VALUE, preferExact = true)
        val wantRaw = currentPrefs.saveMode == ImageSaveMode.RAW_JPEG && supportsRaw(chars)
        val rawSize = if (wantRaw) pickSize(chars, ImageFormat.RAW_SENSOR, Long.MAX_VALUE, aspect = null) else null
        val location = locationProvider()
        val tap = tapMetering(chars)

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
            val uri = try {
                finishJpeg(bytes, jpegSize, chars, location)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to crop photo", e)
                null
            }
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
                        // Keep the focus/exposure warm-up locked on the tapped spot.
                        tap?.let { applyTapMetering(this, chars, it) }
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

    /**
     * Saves the camera's JPEG as is when it already matches the viewfinder, otherwise decodes
     * only the visible region, turns it upright (the camera may have rotated the pixels or
     * only tagged EXIF), mirrors front selfies if wanted and re-encodes it.
     */
    private fun finishJpeg(bytes: ByteArray, size: Size, chars: CameraCharacteristics, location: Location?): Uri? {
        val box = photoAspect()?.let { FrameGeometry.centerCrop(size.width, size.height, it) }
        val crop = box != null && (box.width != size.width || box.height != size.height)
        val mirror = currentPrefs.mirrorSelfies && isFront(chars)
        if (!crop && !mirror) return saveJpegBytes(bytes)
        val orientation = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val region = box?.let { Rect(it.left, it.top, it.left + it.width, it.top + it.height) }
            ?: Rect(0, 0, size.width, size.height)
        // A centered crop of the stored image is the same centered crop after rotating it.
        val decoded = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            .run { try { decodeRegion(region, null) } finally { recycle() } }
            ?: return null
        val upright = rotate(decoded, degrees)
        val finished = if (mirror) {
            Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postScale(-1f, 1f) }, true)
                .also { if (it !== upright) upright.recycle() }
        } else upright
        return mediaSaver.saveJpeg(finished, "IMG", location).also { finished.recycle() }
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

    /** Manual controls, only while the panel is on (same rules as CameraXProvider.applyProConfigLive). */
    private fun applyProConfig(builder: CaptureRequest.Builder) {
        if (!isProMode) return
        val cfg = currentProConfig
        if (cfg.hasManualExposure) {
            // Manual exposure needs both values; fill the missing one like the preview does.
            builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_OFF
            builder[CaptureRequest.SENSOR_SENSITIVITY] = cfg.iso ?: ProConfig.MANUAL_DEFAULT_ISO
            builder[CaptureRequest.SENSOR_EXPOSURE_TIME] = cfg.shutterSpeedNs ?: ProConfig.MANUAL_DEFAULT_SHUTTER_NS
        }
        cfg.focusDistance?.let {
            builder[CaptureRequest.CONTROL_AF_MODE] = CaptureRequest.CONTROL_AF_MODE_OFF
            builder[CaptureRequest.LENS_FOCUS_DISTANCE] = it
        }
        cfg.whiteBalanceKelvin?.let { kelvin ->
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
            onError(
                IllegalStateException(
                    if (isFrontCamera) "Slow motion isn't available on the front camera"
                    else "This device does not support high-speed video"
                )
            )
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
                        applyTorch(this, chars)
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
                    fun request(withControls: Boolean) = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        if (withControls) {
                            // Zoom, brightness and torch are among the keys high-speed sessions accept.
                            applyCommon(this)
                            applyTorch(this, chars)
                        }
                    }.build()
                    try {
                        highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(request(true)), null, backgroundHandler)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "High-speed request rejected with zoom/torch; recording without them", e)
                        highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(request(false)), null, backgroundHandler)
                    }
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

    override fun stopSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val uri = slowMoUri
        var ok = true
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // Thrown when no frames were recorded.
            Log.w(TAG, "MediaRecorder.stop failed", e)
            ok = false
        }
        mediaRecorder?.release()
        mediaRecorder = null
        slowMoPfd?.close()
        slowMoPfd = null
        slowMoUri = null
        closeCamera()

        if (uri == null) {
            onError(IllegalStateException("Not recording"))
            return
        }
        if (ok) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            onSuccess(uri.toString())
        } else {
            context.contentResolver.delete(uri, null, null)
            onError(Exception("Video too short"))
        }
    }

    /** Video "Flash On" is a torch; CameraX is unbound while Camera2 records, so set it here. */
    private fun applyTorch(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val wanted = currentPrefs.lightMode == LightMode.FLASH && !isFrontCamera &&
            chars[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
        if (!wanted) return
        builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON
        builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_TORCH
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

    /**
     * A high-speed capable camera facing the same way as the selected one (never silently the
     * other side: selfie slow motion must not record from the rear camera).
     */
    private fun findHighSpeedCamera(): String? {
        val lensFacing = if (isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val preferred = findCameraId(currentCameraSelector)
        val candidates = listOfNotNull(preferred) + cameraManager.cameraIdList.filter { it != preferred }
        return candidates.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars[CameraCharacteristics.LENS_FACING] == lensFacing &&
                chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
        }
    }

    // ---------------------------------------------------------------------
    // State setters (CameraX owns live preview controls)
    // ---------------------------------------------------------------------

    override fun setProMode(enabled: Boolean) {
        isProMode = enabled
    }

    override fun updateManualConfig(config: ProConfig) {
        currentProConfig = config
    }

    override fun setZoom(ratio: Float) {
        // Applied to Camera2 bursts so Night/HDR respect the selected zoom.
        currentZoomRatio = ratio
    }

    /** Remembers the tap; the next capture meters and focuses there (CameraX reports focus). */
    override fun focusAt(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        focusPoint = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    override fun cancelFocus() {
        focusPoint = null
    }

    override fun setViewfinderAspectRatio(widthOverHeight: Float) {
        if (widthOverHeight > 0f) viewfinderWOverH = widthOverHeight
    }
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

    /** Called on the Camera2 background thread (HybridCaptureEngine moves it to the main thread). */
    override fun setOnCaptureProgressListener(listener: (Float) -> Unit) {
        progressListener = listener
    }
    override fun setOnCodeScanListener(listener: (com.fearmikey.camera.data.engine.analysis.CodeScanFrame) -> Unit) = Unit
    override fun setOnPortraitFacesListener(listener: (com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame) -> Unit) = Unit

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
        const val NIGHT_FRAMES_MIN = 8
        const val NIGHT_FRAMES_MAX = 14
        const val LONG_EXPOSURE_FRAMES_MIN = 16
        const val LONG_EXPOSURE_FRAMES_MAX = 32
        const val LONG_EXPOSURE_TARGET_NS = 2_000_000_000L // planned burst length (back-to-back frames)
        const val DEFAULT_FRAME_DURATION_NS = 33_333_333L // if the stream's minimum is unknown
        const val TIME_LAPSE_CAPTURE_FPS = 2.0
        const val MAX_NIGHT_PIXELS = 12_600_000L // Night and Long exposure stacks
        const val MAX_HDR_PIXELS = 8_400_000L
        const val TAP_REGION_SIZE = 0.1f // tap metering region, fraction of the frame (as CameraX)
        const val TAP_FOCUS_EXTRA_MS = 1000L // extra warm-up time for an AF scan after a tap
        const val HANDHELD_MAX_EXPOSURE_NS = 125_000_000L // 1/8 s per frame at 1x: hand-holdable
        const val HANDHELD_MAX_EXPOSURE_NO_OIS_NS = 66_000_000L // 1/15 s without optical stabilization
        const val HANDHELD_MIN_EXPOSURE_NS = 33_000_000L // never cap below 1/30 s, even zoomed in
        const val NIGHT_EXPOSURE_STRETCH = 4L // at most 4x the metered exposure time
        const val NIGHT_CAPTURE_GAIN = 1.25 // frames slightly brighter than AE; the merge brightens further
        const val MAX_PLAN_GAIN = 4.0 // brightness the sensor couldn't reach, applied in the merge
        const val HDR_MAX_EXPOSURE_NS = 250_000_000L
        const val CAPTURE_TIMEOUT_MS = 30_000L // up to 32 full-resolution frames + merge on slow phones
        // Shutter ring progress per stage (multi-frame captures).
        const val PROGRESS_METERED = 0.08f
        const val STACK_PROGRESS_FRAMES_END = 0.85f
        const val HDR_PROGRESS_FRAMES_END = 0.45f
        const val PROGRESS_MERGED = 0.95f
        const val SLOW_MO_PLAYBACK_FPS = 30
        val HDR_EV_STOPS = listOf(-2f, 0f, 2f)
    }
}
