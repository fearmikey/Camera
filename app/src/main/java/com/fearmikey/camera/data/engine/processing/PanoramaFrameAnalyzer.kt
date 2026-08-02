package com.fearmikey.camera.data.engine.processing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors

class PanoramaFrameAnalyzer(
    private val bridge: NativePanoramaBridge,
    private val enginePtr: Long,
    private val isActive: () -> Boolean,
    private val onBlurDetected: () -> Unit
) : ImageAnalysis.Analyzer {

    private val workerExecutor = Executors.newSingleThreadExecutor()

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (!isActive()) {
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
                    timestamp = timestamp
                )
                if (!success) {
                    onBlurDetected()
                }
            } finally {
                imageProxy.close()
            }
        }
    }
}
