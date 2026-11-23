package com.fearmikey.camera.data.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Zoom buttons that match the hardware: ultrawide (the minimum ratio), 1x, 2x (main-sensor crop),
 * then one button per telephoto lens at its marketed magnification. There are no digital-only
 * stops past the longest lens; pinching still reaches the maximum zoom.
 */
object ZoomPresets {

    private const val TAG = "ZoomPresets"

    /** Marketed telephoto magnifications; a lens maps to the largest one it reaches within 15%. */
    private val TELE_STOPS = listOf(3f, 4f, 5f, 10f)
    private const val TELE_TOLERANCE = 0.85f

    /** 35mm-equivalent focal length that Google Camera labels "1x" on the selfie camera. */
    private const val SELFIE_1X_EQUIVALENT_MM = 24f
    /** Only clearly wide selfie cameras get a sub-1x label (Pixel 7 Pro+: ~17mm -> .7x). */
    private const val MAX_WIDE_SELFIE_SCALE = 0.8f
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    /**
     * Zoom-ratio-to-label factor (CameraHardwareCapabilities.zoomLabelScale): on a wide-angle
     * front camera the full field of view is labeled e.g. ".7x" and "1x" is a normal-selfie
     * crop, as in Google Camera. 1 for rear cameras and ordinary selfie cameras.
     */
    fun labelScale(manager: CameraManager, cameraId: String?, isFront: Boolean): Float {
        if (!isFront || cameraId == null) return 1f
        val lens = runCatching { lensOf(cameraId, manager.getCameraCharacteristics(cameraId)) }.getOrNull() ?: return 1f
        val equivalentMm = lens.focal * FULL_FRAME_DIAGONAL_MM / lens.diagonal
        val scale = Math.round(equivalentMm / SELFIE_1X_EQUIVALENT_MM * 10) / 10f
        Log.i(TAG, "front camera $cameraId: ${"%.1f".format(equivalentMm)}mm equivalent -> label scale $scale")
        return if (scale in 0.4f..MAX_WIDE_SELFIE_SCALE) scale else 1f
    }

    fun compute(
        manager: CameraManager,
        cameraId: String?,
        minZoom: Float,
        maxZoom: Float,
        isFront: Boolean,
        labelScale: Float = 1f,
    ): List<Float> {
        val presets = mutableListOf<Float>()
        if (minZoom < 0.95f) presets += Math.round(minZoom * 10) / 10f
        presets += 1f
        // Wide selfie camera: full view (".7x") plus the crop labeled "1x".
        if (isFront && labelScale < 0.95f && 1f / labelScale <= maxZoom + 0.01f) presets += 1f / labelScale
        if (!isFront) {
            if (maxZoom >= 2f) presets += 2f
            if (cameraId != null) {
                telephotoRatios(manager, cameraId, minZoom).forEach { ratio ->
                    val stop = TELE_STOPS.lastOrNull { ratio >= it * TELE_TOLERANCE }
                    if (stop != null && stop <= maxZoom + 0.01f) presets += stop
                }
            }
        }
        return presets.distinct().sorted()
    }

    /** One physical lens: focal length (mm) and active sensor diagonal (mm). */
    private data class Lens(val id: String, val focal: Float, val diagonal: Float) {
        /** Proportional to the 35mm-equivalent focal length. */
        val equivalent: Float get() = focal / diagonal
    }

    /**
     * Magnification of each distinct lens behind [cameraId] (or each same-facing camera)
     * relative to the main camera.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun telephotoRatios(manager: CameraManager, cameraId: String, minZoom: Float): List<Float> = try {
        val chars = manager.getCameraCharacteristics(cameraId)
        val physical = chars.physicalCameraIds
        val candidates = if (physical.isNotEmpty()) {
            physical.toList()
        } else {
            // Not a logical multi-camera: telephotos may be listed as separate cameras.
            val facing = chars[CameraCharacteristics.LENS_FACING]
            listOf(cameraId) + manager.cameraIdList.filter { id ->
                id != cameraId && runCatching {
                    manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING] == facing
                }.getOrDefault(false)
            }
        }
        val all = candidates.mapNotNull { id ->
            runCatching { lensOf(id, manager.getCameraCharacteristics(id)) }.getOrNull()
        }
        // Some devices (Pixels among them) list the same optics more than once, e.g. a
        // telephoto plus a cropped "sensor zoom" variant of it, which would look twice as
        // long and add a bogus 10x. Same focal length = same lens: keep the full sensor.
        val lenses = mutableListOf<Lens>()
        for (lens in all.sortedByDescending { it.diagonal }) {
            if (lenses.none { abs(it.focal - lens.focal) / it.focal < 0.05f }) lenses += lens
        }
        // 1x is the main camera, which has the largest sensor on every multi-camera phone.
        val main = lenses.maxByOrNull { it.diagonal }
        val ratios = if (main == null) emptyList() else lenses.map { it.equivalent / main.equivalent }
        Log.i(TAG, "camera $cameraId lenses=" + all.joinToString { l ->
            "${l.id}(f=${l.focal}mm, diag=${"%.2f".format(l.diagonal)}mm)"
        } + " kept=" + lenses.map { it.id } + " ratios=" + ratios.map { "%.2f".format(it) })
        ratios.filter { it > 1.5f }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read lens focal lengths: ${e.message}")
        emptyList()
    }

    private fun lensOf(id: String, chars: CameraCharacteristics): Lens? {
        val focal = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull() ?: return null
        val physical = chars[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        val pixels = chars[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        val sx = if (pixels != null && active != null && pixels.width > 0) active.width().toFloat() / pixels.width else 1f
        val sy = if (pixels != null && active != null && pixels.height > 0) active.height().toFloat() / pixels.height else 1f
        val diagonal = hypot(physical.width * sx, physical.height * sy)
        return if (diagonal > 0f && focal > 0f) Lens(id, focal, diagonal) else null
    }
}
