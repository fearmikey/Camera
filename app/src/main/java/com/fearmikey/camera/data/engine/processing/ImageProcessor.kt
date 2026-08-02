package com.fearmikey.camera.data.engine.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.Image
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class ImageProcessor(
    private val context: Context,
    private val portraitSegmenter: PortraitSegmenter,
    private val aligner: FrameAligner = OpenCVFrameAligner(),
    private val gpuProcessor: GpuImageProcessor = GpuImageProcessor()
) {

    init {
        loadShaders()
    }

    private fun loadShaders() {
        val vertexShader = readAsset("shaders/passthrough.glsl")
        val binningOESShader = readAsset("shaders/pixel_binning.glsl")
        val binning2DShader = readAsset("shaders/pixel_binning_2d.glsl")
        gpuProcessor.loadBinningShader(vertexShader, binningOESShader, binning2DShader)
    }

    private fun readAsset(path: String): String {
        return context.assets.open(path).use { inputStream ->
            InputStreamReader(inputStream).readText()
        }
    }

    /**
     * Performs software-based 4x4 Pixel Binning for high SNR 12MP output.
     */
    suspend fun processPixelBinning(imageProxy: ImageProxy): Bitmap? = withContext(Dispatchers.Default) {
        return@withContext gpuProcessor.performPixelBinning(imageProxy, imageProxy.imageInfo.rotationDegrees)
    }

    /**
     * High-performance Night Sight Pipeline using Coroutines and GPU.
     */
    suspend fun processNightSight(images: Flow<Image>, burstSize: Int): Bitmap = withContext(Dispatchers.Default) {
        val buffer = StreamingFrameBuffer(0, 0, burstSize, aligner, gpuProcessor)
        
        images.collectIndexed { index, image ->
            buffer.addFrame(image) { count ->
                // Progress could be reported here
            }
        }
        
        return@withContext buffer.finalize()
    }

    fun createNightSightBuffer(width: Int, height: Int, burstSize: Int): StreamingFrameBuffer {
        return StreamingFrameBuffer(width, height, burstSize, aligner, gpuProcessor)
    }

    fun stackFrames(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("No frames to stack")
        if (bitmaps.size == 1) return bitmaps[0]

        val width = bitmaps[0].width
        val height = bitmaps[0].height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            isAntiAlias = true
        }

        // 1. Conceptual Alignment
        val alignedBitmaps = alignFrames(bitmaps)

        // 2. Weighted Stacking (Accumulative Blending)
        for (i in alignedBitmaps.indices) {
            val alpha = 1.0f / (i + 1)
            paint.alpha = (alpha * 255).toInt()
            canvas.drawBitmap(alignedBitmaps[i], 0f, 0f, paint)
        }

        return result
    }

    /**
     * Advanced HDR Fusion Pipeline:
     * 1. Alignment (Mandatory for multi-exposure)
     * 2. De-ghosting (Detecting movement between frames)
     * 3. Luminance Merging (Weighted by well-exposedness)
     * 4. Tone Mapping (Converting HDR data back to 8-bit)
     */
    fun fuseHDRFrames(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.size < 3) throw IllegalArgumentException("HDR requires at least 3 frames")

        // 1. Frame Alignment (Mandatory)
        val alignedFrames = alignFrames(bitmaps)
        val underExposed = alignedFrames[0] // -2 EV
        val midExposed = alignedFrames[1]   // 0 EV
        val overExposed = alignedFrames[2]  // +2 EV

        val width = midExposed.width
        val height = midExposed.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // 2. De-ghosting & Luminance Merging (Pseudo-logic)
        // In a real implementation, we would work in linear space (decoded RAW or YUV)
        // and use weights based on pixel intensity.
        
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Strategy: Use Over-exposed for shadows, Under-exposed for highlights, Mid for everything else.
        // We use a weighted average where weights are low for clipped (0 or 255) pixels.
        
        // Pseudo-code implementation using Canvas layers for merging:
        canvas.drawBitmap(midExposed, 0f, 0f, paint)
        
        // Merge Shadows (from Over-exposed)
        // We only want to take pixels from 'overExposed' where 'midExposed' is too dark.
        // In real HDR, this is done per-pixel with weight functions.
        val shadowPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
            alpha = 128 // Simple 50% blend for shadows
        }
        canvas.drawBitmap(overExposed, 0f, 0f, shadowPaint)

        // Merge Highlights (from Under-exposed)
        // We only want to take pixels from 'underExposed' where 'midExposed' is too bright (blown out).
        val highlightPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
            alpha = 128 // Simple 50% blend for highlights
        }
        canvas.drawBitmap(underExposed, 0f, 0f, highlightPaint)

        // 3. Tone Mapping (Local Tone Mapping)
        // Simplified: Apply a contrast adjustment to simulate local tone mapping
        // This ensures the sky detail (under) and shadow detail (over) are visible in the 8-bit result.
        return applyLocalToneMapping(result)
    }

    private fun applyLocalToneMapping(bitmap: Bitmap): Bitmap {
        // Conceptually: HDR image -> Log domain -> High-pass filter (details) 
        // -> Compress base layer (dynamic range) -> Combine.
        // For this demo, we'll simulate it with a subtle contrast/brightness boost
        // to make the fused details "pop".
        return bitmap // Placeholder for actual tone mapping shader/algorithm
    }

    private fun alignFrames(bitmaps: List<Bitmap>): List<Bitmap> {
        // Conceptual translation-based alignment
        // In this implementation, we return the frames as-is, 
        // as a true alignment algorithm exceeds the scope of this architecture demo.
        // Integration point for OpenCV/C++ alignment logic.
        return bitmaps
    }

    fun processPortrait(baseBitmap: Bitmap): Bitmap {
        val mask = portraitSegmenter.segment(baseBitmap)
        val bokehBitmap = applyBokeh(baseBitmap, mask)
        return applyPortraitLighting(bokehBitmap, mask)
    }

    private fun applyPortraitLighting(baseBitmap: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, baseBitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Draw the base bokeh image
        canvas.drawBitmap(baseBitmap, 0f, 0f, paint)

        // 2. Apply "Studio Light" to the subject
        // We use the mask to target the lighting effect
        val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Increase brightness and slightly boost contrast
            val cm = ColorMatrix().apply {
                setScale(1.15f, 1.15f, 1.15f, 1f)
            }
            colorFilter = ColorMatrixColorFilter(cm)
        }

        val scaledMask = Bitmap.createScaledBitmap(mask, baseBitmap.width, baseBitmap.height, true)
        
        // Use SRC_IN to only draw where the mask is white
        // We need a temporary layer to apply the color filter through the mask
        canvas.saveLayer(0f, 0f, baseBitmap.width.toFloat(), baseBitmap.height.toFloat(), null)
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        lightingPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(baseBitmap, 0f, 0f, lightingPaint)
        canvas.restore()

        return result
    }

    private fun applyBokeh(baseBitmap: Bitmap, mask: Bitmap): Bitmap {
        // 1. Create a blurred version of the original image (Stronger blur for background)
        val blurredBitmap = blurBitmap(baseBitmap, radius = 60)

        // 2. Combine using the mask
        val result = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, baseBitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw the blurred background first
        canvas.drawBitmap(blurredBitmap, 0f, 0f, paint)

        // 3. Smooth the mask to create a soft transition
        // Use a smaller radius for mask smoothing to keep the subject edges relatively defined
        val scaledMask = Bitmap.createScaledBitmap(mask, baseBitmap.width, baseBitmap.height, true)
        val smoothedMask = blurBitmap(scaledMask, radius = 5)

        // 4. Draw the sharp subject on top using the smoothed mask
        // Mask: White = Subject, Black = Background
        canvas.saveLayer(0f, 0f, baseBitmap.width.toFloat(), baseBitmap.height.toFloat(), null)
        canvas.drawBitmap(smoothedMask, 0f, 0f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(baseBitmap, 0f, 0f, paint)
        canvas.restore()

        return result
    }

    private fun blurBitmap(bitmap: Bitmap, radius: Int): Bitmap {
        // More aggressive downscaling for a more noticeable blur.
        // We allow the scale to go down to 0.05f (20x downscale) for strong background bokeh.
        val scale = (1.0f / (radius.coerceAtLeast(1) / 2f)).coerceIn(0.05f, 0.5f)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return Bitmap.createScaledBitmap(smallBitmap, bitmap.width, bitmap.height, true)
    }
}

