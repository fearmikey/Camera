package com.fearmikey.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomPolicyTest {

    private val pixelRearPresets = listOf(0.5f, 1f, 2f, 5f)
    private val wideSelfiePresets = listOf(1f, 1f / 0.7f)

    @Test
    fun rearPortraitStartsAt2xWith1xAnd2xAndPinchTo3x() {
        val p = ZoomPolicy.forMode(CameraMode.PORTRAIT, false, 0.5f, 30f, pixelRearPresets, 1f)
        assertEquals(listOf(1f, 2f), p.presets)
        assertEquals(2f, p.defaultRatio)
        assertEquals(1f, p.min)
        assertEquals(3f, p.max)
        assertEquals(1f, p.clamp(0.5f))
        assertEquals(3f, p.clamp(5f))
    }

    @Test
    fun rearPortraitOnACameraWithoutMuchZoomStaysInRange() {
        val p = ZoomPolicy.forMode(CameraMode.PORTRAIT, false, 1f, 1.5f, listOf(1f), 1f)
        assertEquals(listOf(1f), p.presets)
        assertEquals(1.5f, p.defaultRatio)
        assertEquals(1.5f, p.max)
    }

    @Test
    fun frontPortraitOnWideSelfieCameraStartsAtPoint7AndPinchesTo2Point9Label() {
        val p = ZoomPolicy.forMode(CameraMode.PORTRAIT, true, 1f, 8f, wideSelfiePresets, 0.7f)
        assertEquals(wideSelfiePresets, p.presets)
        assertEquals(1f, p.defaultRatio) // full view, labeled .7x
        assertEquals(2.9f, p.max * 0.7f, 0.001f)
    }

    @Test
    fun frontPortraitIsCappedByTheCamera() {
        val p = ZoomPolicy.forMode(CameraMode.PORTRAIT, true, 1f, 2f, wideSelfiePresets, 0.7f)
        assertEquals(2f, p.max)
    }

    @Test
    fun otherModesUseTheCameraRangeAndPresets() {
        val p = ZoomPolicy.forMode(CameraMode.PHOTO, false, 0.5f, 30f, pixelRearPresets, 1f)
        assertEquals(pixelRearPresets, p.presets)
        assertEquals(1f, p.defaultRatio)
        assertEquals(0.5f, p.min)
        assertEquals(30f, p.max)
    }
}
