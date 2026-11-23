package com.fearmikey.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameGeometryTest {

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals("x", expected.first, actual.first, 1e-4f)
        assertEquals("y", expected.second, actual.second, 1e-4f)
    }

    @Test
    fun `photo aspect follows the setting and the measured full-screen viewfinder`() {
        assertEquals(4f / 3f, FrameGeometry.photoAspect(AspectRatio.RATIO_4_3, null)!!, 1e-6f)
        assertEquals(16f / 9f, FrameGeometry.photoAspect(AspectRatio.RATIO_16_9, 0.3f)!!, 1e-6f)
        // Portrait 9:20 viewfinder -> long/short 20/9.
        assertEquals(20f / 9f, FrameGeometry.photoAspect(AspectRatio.RATIO_FULL, 9f / 20f)!!, 1e-5f)
        assertNull(FrameGeometry.photoAspect(AspectRatio.RATIO_FULL, null))
    }

    @Test
    fun `center crop keeps orientation and is a no-op for matching shapes`() {
        assertEquals(FrameGeometry.Box(0, 0, 4000, 3000), FrameGeometry.centerCrop(4000, 3000, 4f / 3f))
        // Landscape 4:3 -> 16:9: trim top and bottom.
        assertEquals(FrameGeometry.Box(0, 375, 4000, 2250), FrameGeometry.centerCrop(4000, 3000, 16f / 9f))
        // Portrait 3:4 -> 9:16: trim the sides.
        assertEquals(FrameGeometry.Box(375, 0, 2250, 4000), FrameGeometry.centerCrop(3000, 4000, 16f / 9f))
        // 16:9 source to a squarer 4:3: trim the long side.
        assertEquals(FrameGeometry.Box(480, 0, 2880, 2160), FrameGeometry.centerCrop(3840, 2160, 4f / 3f))
    }

    @Test
    fun `size picking prefers exact shapes for camera JPEGs and most cropped pixels otherwise`() {
        val sizes = listOf(4080 to 3072, 3840 to 2160, 4624 to 2600, 1920 to 1080)
        // No aspect: largest under the cap (4080x3072 = 12.5 MP).
        assertEquals(4080 to 3072, FrameGeometry.pickSize(sizes, Long.MAX_VALUE, null, preferExact = false))
        // 16:9 exact: the largest 16:9 size.
        assertEquals(4624 to 2600, FrameGeometry.pickSize(sizes, Long.MAX_VALUE, 16f / 9f, preferExact = true))
        // 12.6 MP cap, bitmap pipeline: 4624x2600 (12.0 MP, no crop) beats 4080x3072 cropped to 9.4 MP.
        assertEquals(4624 to 2600, FrameGeometry.pickSize(sizes, 12_600_000L, 16f / 9f, preferExact = false))
        // 20:9 has no exact size: keep the most pixels after cropping.
        assertEquals(4624 to 2600, FrameGeometry.pickSize(sizes, Long.MAX_VALUE, 20f / 9f, preferExact = true))
        // Nothing fits the cap: the smallest size.
        assertEquals(1920 to 1080, FrameGeometry.pickSize(sizes, 1_000_000L, 4f / 3f, preferExact = false))
    }

    @Test
    fun `tap maps through rotation for the rear camera`() {
        val map = { x: Float, y: Float ->
            FrameGeometry.viewfinderToSensor(x, y, 3f / 4f, 4f / 3f, 4f / 3f, sensorOrientation = 90, isFront = false)
        }
        assertPoint(0.5f to 0.5f, map(0.5f, 0.5f))
        // Upright top-left is the sensor's bottom-left (the image is turned 90 degrees clockwise).
        assertPoint(0f to 1f, map(0f, 0f))
        // Upright top-right is the sensor's top-left.
        assertPoint(0f to 0f, map(1f, 0f))
    }

    @Test
    fun `tap maps through the mirror for the front camera`() {
        val p = FrameGeometry.viewfinderToSensor(0.25f, 0.5f, 3f / 4f, 4f / 3f, 4f / 3f, sensorOrientation = 270, isFront = true)
        // Unmirror: x = 0.75; undo 270: sensor x = 1 - y = 0.5, sensor y = x = 0.75.
        assertPoint(0.5f to 0.75f, p)
    }

    @Test
    fun `full-screen viewfinder only shows the middle of the 4-3 stream`() {
        // 9:20 viewfinder over a 3:4 upright stream: only 0.45/0.75 = 60% of the width is visible,
        // so the left edge is 20% into the stream (upright x 0.2, y 0.5).
        // Undo the 90 degree rotation: sensor x = upright y = 0.5, sensor y = 1 - upright x = 0.8.
        val left = FrameGeometry.viewfinderToSensor(0f, 0.5f, 9f / 20f, 4f / 3f, 4f / 3f, 90, isFront = false)
        assertPoint(0.5f to 0.8f, left)
    }

    @Test
    fun `16-9 stream is a band of the 4-3 active array`() {
        // Sensor orientation 0 keeps the maths easy: the stream spans 75% of the active height.
        val top = FrameGeometry.viewfinderToSensor(0.5f, 0f, 16f / 9f, 16f / 9f, 4f / 3f, 0, isFront = false)
        assertPoint(0.5f to 0.125f, top)
    }
}
