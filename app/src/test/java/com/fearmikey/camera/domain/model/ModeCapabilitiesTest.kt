package com.fearmikey.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeCapabilitiesTest {

    private fun caps(
        mode: CameraMode,
        prefs: UserPreferences = UserPreferences(),
        front: Boolean = false,
        dark: Boolean = false,
        manualExposure: Boolean = false,
    ) = ModeCapabilities.forMode(mode, front, prefs, dark, manualExposure)

    @Test
    fun rearPhotoOffersEverything() {
        val c = caps(CameraMode.PHOTO)
        assertEquals(CapturePipeline.PHOTO, c.pipeline)
        assertEquals(LightMode.entries.toList(), c.lightModes)
        assertTrue(c.showsResolutionChip)
        assertTrue(c.showsMacro)
        assertTrue(c.supportsTimer)
        assertTrue(c.supportsQuickTake)
        assertTrue(c.supportsManualControls)
        assertTrue(c.offersAdjustments)
        assertFalse(c.supportsSlowMotionSpeed)
        assertFalse(c.supportsVideoSnapshot)
    }

    @Test
    fun rawJpegIsASingleCamera2StillWithoutFlashOrResolutionChip() {
        val c = caps(CameraMode.PHOTO, UserPreferences(saveMode = ImageSaveMode.RAW_JPEG, hdrPhoto = true, lightMode = LightMode.FLASH))
        assertEquals(CapturePipeline.RAW_STILL, c.pipeline)
        assertTrue(c.lightModes.isEmpty())
        assertNull(c.effectiveLightMode)
        assertFalse(c.showsResolutionChip)
        assertTrue(c.supportsManualControls)
    }

    @Test
    fun autoNightTakesDarkPhotosButNotWithManualExposure() {
        val prefs = UserPreferences(lightMode = LightMode.AUTO_NIGHT_SIGHT)
        assertEquals(CapturePipeline.PHOTO, caps(CameraMode.PHOTO, prefs).pipeline)
        val dark = caps(CameraMode.PHOTO, prefs, dark = true)
        assertEquals(CapturePipeline.NIGHT, dark.pipeline)
        assertTrue(dark.autoNightActive)
        // Controls don't depend on the live scene.
        assertTrue(dark.showsResolutionChip)
        assertTrue(dark.supportsManualControls)
        assertEquals(CapturePipeline.PHOTO, caps(CameraMode.PHOTO, prefs, dark = true, manualExposure = true).pipeline)
    }

    @Test
    fun autoNightBeatsHdrInTheDark() {
        val prefs = UserPreferences(lightMode = LightMode.AUTO_NIGHT_SIGHT, hdrPhoto = true)
        assertEquals(CapturePipeline.HDR, caps(CameraMode.PHOTO, prefs).pipeline)
        assertEquals(CapturePipeline.NIGHT, caps(CameraMode.PHOTO, prefs, dark = true).pipeline)
    }

    @Test
    fun hdrHidesResolutionChipAndManualControlsButKeepsFlash() {
        val c = caps(CameraMode.PHOTO, UserPreferences(hdrPhoto = true))
        assertEquals(CapturePipeline.HDR, c.pipeline)
        assertFalse(c.showsResolutionChip)
        assertFalse(c.supportsManualControls)
        assertFalse(c.offersAdjustments)
        assertEquals(LightMode.entries.toList(), c.lightModes)
        assertFalse(c.hdrPausedByFlash)
    }

    @Test
    fun flashWinsOverHdrBecauseOnlyCameraXFiresIt() {
        for (light in listOf(LightMode.AUTO_FLASH, LightMode.FLASH)) {
            val c = caps(CameraMode.PHOTO, UserPreferences(hdrPhoto = true, lightMode = light))
            assertEquals(CapturePipeline.PHOTO, c.pipeline)
            assertTrue(c.hdrPausedByFlash)
            assertTrue(c.supportsManualControls)
        }
    }

    @Test
    fun frontPhotoIgnoresRearOnlyOptions() {
        val c = caps(CameraMode.PHOTO, UserPreferences(hdrPhoto = true, saveMode = ImageSaveMode.RAW_JPEG), front = true)
        assertEquals(CapturePipeline.PHOTO, c.pipeline)
        assertTrue(c.lightModes.isEmpty())
        assertFalse(c.showsResolutionChip)
        assertFalse(c.showsPhotoOutputSettings)
        assertFalse(c.showsMacro)
    }

    @Test
    fun portraitHasNoAutoNight() {
        val c = caps(CameraMode.PORTRAIT, UserPreferences(lightMode = LightMode.AUTO_NIGHT_SIGHT))
        assertEquals(listOf(LightMode.NONE, LightMode.AUTO_FLASH, LightMode.FLASH), c.lightModes)
        assertEquals(LightMode.NONE, c.effectiveLightMode)
        assertEquals(LightMode.AUTO_FLASH, c.nextLightMode())
        assertFalse(c.autoNightActive)
        assertTrue(c.supportsQuickTake)
        assertTrue(c.supportsManualControls)
    }

    @Test
    fun photoFlashCycleWraps() {
        val c = caps(CameraMode.PHOTO, UserPreferences(lightMode = LightMode.FLASH))
        assertEquals(LightMode.NONE, c.nextLightMode())
    }

    @Test
    fun videoFlashIsATorch() {
        val c = caps(CameraMode.VIDEO, UserPreferences(lightMode = LightMode.AUTO_FLASH))
        assertTrue(c.flashIsTorch)
        assertEquals(listOf(LightMode.NONE, LightMode.FLASH), c.lightModes)
        assertEquals(LightMode.NONE, c.effectiveLightMode)
        assertEquals(LightMode.FLASH, c.nextLightMode())
        assertTrue(c.supportsVideoSnapshot)
        assertTrue(c.supportsZoomWhileRecording)
        assertTrue(c.showsVideoQualitySettings)
        assertFalse(c.supportsTimer)
    }

    @Test
    fun camera2VideoModesFixZoomAndHaveNoSnapshotsOrManualControls() {
        for (mode in listOf(CameraMode.SLOW_MO, CameraMode.TIME_LAPSE)) {
            val c = caps(mode)
            assertTrue(c.pipeline.usesCamera2)
            assertFalse(c.supportsVideoSnapshot)
            assertFalse(c.supportsZoomWhileRecording)
            assertFalse(c.supportsManualControls)
            // Exposure compensation still applies: the adjustments row opens (without ISO etc.).
            assertTrue(c.offersAdjustments)
            assertEquals(mode == CameraMode.SLOW_MO, c.supportsSlowMotionSpeed)
            assertFalse(c.supportsQuickTake)
            assertFalse(c.showsVideoQualitySettings)
            assertTrue(c.flashIsTorch)
        }
    }

    @Test
    fun multiFrameModesHaveNoFlashOrManualControlsAndReportProgress() {
        for (mode in listOf(CameraMode.NIGHT_SIGHT, CameraMode.LONG_EXPOSURE)) {
            val c = caps(mode, UserPreferences(lightMode = LightMode.FLASH))
            assertTrue(c.lightModes.isEmpty())
            assertFalse(c.supportsManualControls)
            assertFalse(c.supportsQuickTake)
            assertTrue(c.supportsTimer)
            assertTrue(c.pipeline.reportsProgress)
            assertTrue(c.pipeline.showsProcessingOverlay)
            assertFalse(c.showsResolutionChip)
        }
    }

    @Test
    fun panoramaHasNoTimerFlashOrManualControls() {
        val c = caps(CameraMode.PANORAMA, UserPreferences(timer = TimerMode.SEC_3, lightMode = LightMode.FLASH))
        assertFalse(c.supportsTimer)
        assertTrue(c.lightModes.isEmpty())
        assertFalse(c.supportsManualControls)
        assertFalse(c.offersAdjustments)
        assertFalse(c.supportsQuickTake)
    }
}
