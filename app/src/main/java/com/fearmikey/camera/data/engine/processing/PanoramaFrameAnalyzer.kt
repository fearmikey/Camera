package com.fearmikey.camera.data.engine.processing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors

class PanoramaFrameAnalyzer(
    private val bridge: NativePanoramaBridge,
    private val enginePtr: Long,
    private val isActive: () -> Boolean,
    private val getCurrentYaw: () -> Float,
    private val onBlurDetected: () -> Unit,
) : ImageAnalysis.Analyzer {

    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var lastCapturedYaw: Float? = null
    private val minMovementDegrees = 2.5f // Only capture if moved at least 2.5 degrees

    override fun analyze(imageProxy: ImageProxy) {
        if (!isActive()) {
            lastCapturedYaw = null
            imageProxy.close()
            return
        }

        val currentYaw = getCurrentYaw()
        val lastYaw = lastCapturedYaw
        
        // Filter: Only process frame if it's the first frame OR if we've moved significantly
        if (lastYaw != null && (kotlin.math.abs(currentYaw - lastYaw) < minMovementDegrees)) {
            imageProxy.close()
            return
        }

        val plane = imageProxy.planes[0]
        val buffer = plane.buffer

        if (!buffer.isDirect) {
            imageProxy.close()
            return
        }

        val width = imageProxy.width
        val height = imageProxy.height
        val stride = plane.rowStride
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val timestamp = imageProxy.imageInfo.timestamp

        workerExecutor.execute {
            try {
                val success = bridge.nativeProcessFrame(
                    enginePtr = enginePtr,
                    buffer = buffer,
                    width = width,
                    height = height,
                    stride = stride,
                    format = 1, // RGBA_8888 expected
                    rotationDegrees = rotationDegrees,
                    timestamp = timestamp
                )
                if (success) {
                    lastCapturedYaw = currentYaw
                } else {
                    onBlurDetected()
                }
            } finally {
                imageProxy.close()
            }
        }
    }
}
