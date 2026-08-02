package com.fearmikey.camera.data.engine.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class PortraitSegmenter(
    private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    init {
        try {
            val modelBuffer = loadModelFile()
            gpuDelegate = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Throwable) {
            Log.e("PortraitSegmenter", "Failed to initialize TFLite with GPU: ${e.message}")
            // Fallback to CPU if GPU fails
            try {
                interpreter = Interpreter(loadModelFile())
            } catch (ex: Throwable) {
                Log.e("PortraitSegmenter", "Failed to initialize TFLite on CPU: ${ex.message}")
            }
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        // Placeholder: Assuming portrait_segmentation.tflite exists in assets
        // If not, this will fail at runtime until the user adds it.
        val fileDescriptor = context.assets.openFd("portrait_segmentation.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun segment(bitmap: Bitmap): Bitmap {
        val tInterpreter = interpreter
        if (tInterpreter == null) {
            Log.w("PortraitSegmenter", "TFLite Interpreter is null, using fallback mask")
            return createFallbackMask(bitmap.width, bitmap.height)
        }

        try {
            // 1. Preprocess the image
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(257, 257, ResizeOp.ResizeMethod.BILINEAR)) // Common DeepLab size
                .add(NormalizeOp(127.5f, 127.5f))
                .build()

            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // 2. Prepare output buffer
            val outputMask = Array(1) { Array(257) { FloatArray(257) } }

            // 3. Run inference
            tInterpreter.run(tensorImage.buffer, outputMask)

            // 4. Post-process: Convert float mask to Bitmap
            return convertMaskToBitmap(outputMask[0])
        } catch (e: Exception) {
            Log.e("PortraitSegmenter", "Inference failed", e)
            return createFallbackMask(bitmap.width, bitmap.height)
        }
    }

    /**
     * Creates a simple elliptical mask as a fallback when AI segmentation fails.
     */
    private fun createFallbackMask(width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(Color.BLACK)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        
        // Draw an oval in the center where the subject is likely to be.
        // We reduce the size so that the background blur is more visible.
        val centerX = width / 2f
        val centerY = height / 2f
        val radiusX = width * 0.28f
        val radiusY = height * 0.38f
        
        canvas.drawOval(
            centerX - radiusX, 
            centerY - radiusY, 
            centerX + radiusX, 
            centerY + radiusY, 
            paint
        )
        
        return mask
    }

    private fun convertMaskToBitmap(mask: Array<FloatArray>): Bitmap {
        val width = mask.size
        val height = mask[0].size
        val conf = Bitmap.Config.ARGB_8888
        val maskBitmap = Bitmap.createBitmap(width, height, conf)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Thresholding: > 0.5 is subject (white), <= 0.5 is background (black)
                val color = if (mask[x][y] > 0.5f) Color.WHITE else Color.BLACK
                maskBitmap.setPixel(x, y, color)
            }
        }
        return maskBitmap
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
