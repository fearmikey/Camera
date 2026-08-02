package com.fearmikey.camera.data.engine.processing

import android.graphics.Matrix
import android.media.Image
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A memory-efficient buffer that processes frames iteratively to avoid OOM.
 * It keeps a reference frame for alignment and an accumulator for the merged result.
 */
class StreamingFrameBuffer(
    private val width: Int,
    private val height: Int,
    private val burstSize: Int,
    private val aligner: FrameAligner,
    private val gpuProcessor: GpuImageProcessor
) {
    private var referenceFrame: Image? = null
    private var frameCount = 0
    
    // The accumulator is maintained in GPU memory
    private var isInitialized = false

    /**
     * Adds a new frame to the pipeline. 
     * The frame is processed and then MUST be closed by the caller or this class.
     */
    fun addFrame(image: Image, onFrameProcessed: (Int) -> Unit) {
        if (referenceFrame == null) {
            // Store the first frame as reference
            referenceFrame = image 
            // Initialize GPU accumulator with the first frame
            gpuProcessor.initAccumulator(image)
            isInitialized = true
        } else {
            // 1. Calculate Alignment
            val matrix = aligner.calculateAlignment(referenceFrame!!, image)
            
            // 2. Merge into accumulator on GPU
            val weight = 1.0f / (frameCount + 1)
            gpuProcessor.mergeFrame(image, matrix, weight)
            
            // 3. Close the current image to free memory
            image.close()
        }
        
        frameCount++
        onFrameProcessed(frameCount)
    }

    fun isComplete(): Boolean = frameCount >= burstSize

    /**
     * Finalizes the processing and returns the resulting Bitmap.
     */
    suspend fun finalize(): Bitmap = withContext(Dispatchers.Default) {
        // 1. Denoise and Tone Map on GPU
        val result = gpuProcessor.readResult()
        
        cleanup()
        return@withContext result
    }

    private fun cleanup() {
        referenceFrame?.close()
        referenceFrame = null
        gpuProcessor.release()
    }
}
