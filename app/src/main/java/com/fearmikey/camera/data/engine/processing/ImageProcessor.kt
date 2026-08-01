package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

class ImageProcessor(
    private val portraitSegmenter: PortraitSegmenter
) {

    fun stackFrames(framePaths: List<String>): Bitmap {
        if (framePaths.isEmpty()) throw IllegalArgumentException("No frames to stack")
        return BitmapFactory.decodeFile(framePaths[0])
    }

    fun processPortrait(baseBitmap: Bitmap): Bitmap {
        val mask = portraitSegmenter.segment(baseBitmap)
        return applyBokeh(baseBitmap, mask)
    }

    private fun applyBokeh(baseBitmap: Bitmap, mask: Bitmap): Bitmap {
        // 1. Create a blurred version of the original image
        val blurredBitmap = blurBitmap(baseBitmap, radius = 25)

        // 2. Combine using the mask
        val result = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, baseBitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw the sharp subject first
        canvas.drawBitmap(baseBitmap, 0f, 0f, paint)

        // Use the mask to "punch out" the background and draw the blurred version
        // Mask: White = Subject, Black = Background
        // We want to draw blurred where mask is black.
        
        // This is a simplified blending logic
        val scaledMask = Bitmap.createScaledBitmap(mask, baseBitmap.width, baseBitmap.height, true)
        
        // Use Xfermode to blend
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
        canvas.drawBitmap(blurredBitmap, 0f, 0f, paint)

        return result
    }

    private fun blurBitmap(bitmap: Bitmap, radius: Int): Bitmap {
        // In a real production app, use a fast blur algorithm like StackBlur 
        // or a GPU-accelerated shader (Vulkan/RenderScript).
        // Scaling down and back up is a "poor man's" blur for this architecture demo.
        val scale = 0.2f
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return Bitmap.createScaledBitmap(smallBitmap, bitmap.width, bitmap.height, true)
    }
}

