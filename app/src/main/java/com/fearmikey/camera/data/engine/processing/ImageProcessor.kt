package com.fearmikey.camera.data.engine.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Facade over the image processing pipelines:
 *  - GPU pixel binning (GLSL, [GpuImageProcessor])
 *  - Night mode stacking ([NightStacker], OpenCV)
 *  - HDR exposure fusion (OpenCV Mertens)
 *  - Portrait background blur ([PortraitSegmenter], OpenCV GrabCut + lens bokeh)
 */
class ImageProcessor(
    private val context: Context,
    private val portraitSegmenter: PortraitSegmenter = PortraitSegmenter(),
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

    /** GPU-based pixel binning for a high-SNR ~12MP output. */
    suspend fun processPixelBinning(imageProxy: ImageProxy): Bitmap? = withContext(Dispatchers.Default) {
        gpuProcessor.performPixelBinning(imageProxy, imageProxy.imageInfo.rotationDegrees)
    }

    fun createNightStacker(width: Int, height: Int, longExposure: Boolean = false): NightStacker =
        NightStacker(width, height, longExposure)

    /**
     * Aligns and fuses an exposure bracket (any order) into a single image using
     * Mertens exposure fusion. Returns null on failure.
     */
    fun fuseHdrFrames(frames: List<Bitmap>): Bitmap? {
        if (frames.size < 2) return frames.firstOrNull()
        val inputs = frames.map {
            if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
        }
        val out = Bitmap.createBitmap(inputs[0].width, inputs[0].height, Bitmap.Config.ARGB_8888)
        val ok = NativeVisionBridge.nativeFuseExposures(inputs.toTypedArray(), out)
        inputs.forEachIndexed { i, b -> if (b !== frames[i]) b.recycle() }
        if (!ok) {
            Log.e(TAG, "HDR fusion failed")
            out.recycle()
            return null
        }
        return out
    }

    /**
     * Applies portrait background blur at [strength] (1.0 ~ f/2.8, see PortraitAperture).
     * Returns the processed bitmap, which may be [baseBitmap] itself (modified in place).
     */
    fun processPortrait(baseBitmap: Bitmap, strength: Float = 1f): Bitmap =
        portraitSegmenter.applyBokeh(baseBitmap, strength)

    private companion object {
        const val TAG = "ImageProcessor"
    }
}
