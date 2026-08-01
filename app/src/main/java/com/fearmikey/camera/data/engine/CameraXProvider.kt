package com.fearmikey.camera.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.domain.model.CameraMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class CameraXProvider(
    private val context: Context,
    private val processor: ImageProcessor
) : CaptureEngine {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var currentMode: CameraMode = CameraMode.PHOTO

    init {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    override fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        cameraSelector: CameraSelector
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun setMode(mode: CameraMode) {
        currentMode = mode
    }

    override fun capturePhoto(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        when (currentMode) {
            CameraMode.NIGHT -> captureBurst(3, onSuccess, onError)
            CameraMode.PORTRAIT -> capturePortrait(onSuccess, onError)
            else -> captureSinglePhoto(onSuccess, onError)
        }
    }

    override fun startSlowMoVideo(onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        // Slow-mo not supported in CameraXProvider yet
        onError(UnsupportedOperationException("Slow-mo not supported in CameraXProvider"))
    }

    override fun stopSlowMoVideo() {
        // No-op
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
                        val resultBitmap = processor.stackFrames(capturedPaths)
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
