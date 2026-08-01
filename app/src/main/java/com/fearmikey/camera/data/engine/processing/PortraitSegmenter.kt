package com.fearmikey.camera.data.engine.processing

import android.content.Context
import android.graphics.Bitmap
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
    private val gpuDelegate: GpuDelegate by lazy { GpuDelegate() }

    init {
        try {
            val options = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }
            interpreter = Interpreter(loadModelFile(), options)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to CPU if GPU fails
            try {
                interpreter = Interpreter(loadModelFile())
            } catch (ex: Exception) {
                ex.printStackTrace()
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
        val tInterpreter = interpreter ?: return bitmap

        // 1. Preprocess the image
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(257, 257, ResizeOp.ResizeMethod.BILINEAR)) // Common DeepLab size
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Prepare output buffer
        // DeepLab output is typically [1, 257, 257, num_classes] or [1, 257, 257]
        // Assuming binary mask for simplicity
        val outputMask = Array(1) { Array(257) { FloatArray(257) } }

        // 3. Run inference
        tInterpreter.run(tensorImage.buffer, outputMask)

        // 4. Post-process: Convert float mask to Bitmap
        return convertMaskToBitmap(outputMask[0])
    }

    private fun convertMaskToBitmap(mask: Array<FloatArray>): Bitmap {
        val width = mask.size
        val height = mask[0].size
        val conf = Bitmap.Config.ARGB_8888
        val maskBitmap = Bitmap.createBitmap(width, height, conf)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Thresholding: > 0.5 is subject (white), <= 0.5 is background (black)
                val color = if (mask[x][y] > 0.5f) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                maskBitmap.setPixel(x, y, color)
            }
        }
        return maskBitmap
    }

    fun close() {
        interpreter?.close()
        gpuDelegate.close()
    }
}
