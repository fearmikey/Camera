package com.fearmikey.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlowMotionTest {

    // A typical phone: 1080p up to 120 fps, 720p up to 240 fps, plus a 4K entry that is skipped.
    private val phone = listOf(
        HighSpeedConfig(1920, 1080, 120),
        HighSpeedConfig(1280, 720, 120),
        HighSpeedConfig(1280, 720, 240),
        HighSpeedConfig(3840, 2160, 120),
        HighSpeedConfig(1920, 1080, 60), // below slow-motion rates
    )

    @Test
    fun ratesAreDistinctAscendingAndSkipSlowOrHugeStreams() {
        assertEquals(listOf(120, 240), SlowMotion.rates(phone))
        assertEquals(emptyList<Int>(), SlowMotion.rates(listOf(HighSpeedConfig(3840, 2160, 120))))
    }

    @Test
    fun pickTakesTheLargestStreamForTheRate() {
        assertEquals(HighSpeedConfig(1920, 1080, 120), SlowMotion.pick(phone, 120))
        assertEquals(HighSpeedConfig(1280, 720, 240), SlowMotion.pick(phone, 240))
        assertNull(SlowMotion.pick(phone, 480))
    }

    @Test
    fun pickPrefers16by9() {
        val configs = listOf(HighSpeedConfig(1440, 1080, 120), HighSpeedConfig(1280, 720, 120))
        assertEquals(HighSpeedConfig(1280, 720, 120), SlowMotion.pick(configs, 120))
    }

    @Test
    fun storedRateFallsBackToTheClosestOffered() {
        assertEquals(240, SlowMotion.resolveFps(240, listOf(120, 240)))
        assertEquals(120, SlowMotion.resolveFps(240, listOf(120)))
        assertEquals(240, SlowMotion.resolveFps(120, listOf(240, 480)))
        assertNull(SlowMotion.resolveFps(120, emptyList()))
    }

    @Test
    fun bitRateScalesWithSizeWithinBounds() {
        assertEquals(20_000_000, SlowMotion.bitRate(HighSpeedConfig(1920, 1080, 120)))
        assertEquals(8_888_888, SlowMotion.bitRate(HighSpeedConfig(1280, 720, 240)))
        assertEquals(8_000_000, SlowMotion.bitRate(HighSpeedConfig(640, 480, 240)))
    }

    @Test
    fun speedLabels() {
        assertEquals("1/4x", SlowMotion.speedLabel(120))
        assertEquals("1/8x", SlowMotion.speedLabel(240))
    }
}
