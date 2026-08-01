package com.fearmikey.camera.data.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
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

class Camera2Provider(
    private val context: Context
) : CaptureEngine {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoPath: String? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentMode: CameraMode = CameraMode.PHOTO
    private var previewSurface: Surface? = null
    private var lifecycleOwner: LifecycleOwner? = null

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

    @SuppressLint("MissingPermission")
    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        this.lifecycleOwner = lifecycleOwner
        startBackgroundThread()
    }

    override fun setMode(mode: CameraMode) {
        val oldMode = currentMode
        currentMode = mode
        if (oldMode != CameraMode.SLOW_MO && mode == CameraMode.SLOW_MO) {
            previewSurface?.let { surface ->
                cameraDevice?.close()
                findHighSpeedCamera()?.let { openCameraForPreview(it, surface) }
            }
        } else if (oldMode == CameraMode.SLOW_MO && mode != CameraMode.SLOW_MO) {
            cameraDevice?.close()
            cameraDevice = null
            captureSession?.close()
            captureSession = null
        }
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        // Implement burst capture for Night mode using Camera2
    }

    override fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        startBackgroundThread()
        val cameraId = findHighSpeedCamera()
        if (cameraId == null) {
            onError(IllegalStateException("No high-speed camera found"))
            return
        }

        openCameraAndStartHighSpeedSession(cameraId, onSuccess, onError)
    }

    override fun stopSlowMoVideo() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            captureSession?.close()
            cameraDevice?.close()
            
            currentVideoPath?.let { path ->
                saveToMediaStore(path)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopBackgroundThread()
        }
    }

    private fun findHighSpeedCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
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
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val targetSize = configs?.getHighSpeedVideoSizes()?.firstOrNull { it.width <= 1920 } ?: Size(1280, 720)
        val targetFpsRange = configs?.getHighSpeedVideoFpsRangesFor(targetSize)?.maxByOrNull { it.upper } ?: Range(120, 120)

        camera.createConstrainedHighSpeedCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder.addTarget(surface)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
                
                val requestList = highSpeedSession.createHighSpeedRequestList(builder.build())
                highSpeedSession.setRepeatingBurst(requestList, null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
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
        }, backgroundHandler)
    }

    private fun setupMediaRecorderAndSession(
        camera: CameraDevice,
        cameraId: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val targetSize = configs?.getHighSpeedVideoSizes()?.firstOrNull { it.width <= 1920 } ?: Size(1280, 720)
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
        
        camera.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(recorderSurface)
                previewSurface?.let { builder.addTarget(it) }
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
                
                val requestList = highSpeedSession.createHighSpeedRequestList(builder.build())
                highSpeedSession.setRepeatingBurst(requestList, null, backgroundHandler)
                
                mediaRecorder?.start()
                onSuccess(videoFile.absolutePath)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(Exception("Session configuration failed"))
            }
        }, backgroundHandler)
    }

    private fun saveToMediaStore(path: String) {
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
        }
    }

    private fun createVideoFile(): File {
        return File(
            context.externalCacheDir,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"
        )
    }
}
