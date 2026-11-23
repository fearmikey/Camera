package com.fearmikey.camera.domain.model

import androidx.compose.runtime.Immutable

/** The capture pipeline a shutter press runs. */
enum class CapturePipeline(
    /** CameraX is released and the shot (or recording) runs on raw Camera2. */
    val usesCamera2: Boolean,
    /** The engine reports 0..1 progress (CaptureEngine.setOnCaptureProgressListener). */
    val reportsProgress: Boolean,
    /** Slow enough to dim the viewfinder with a full-screen progress overlay. */
    val showsProcessingOverlay: Boolean,
) {
    /** CameraX single or GPU-binned photo. */
    PHOTO(usesCamera2 = false, reportsProgress = false, showsProcessingOverlay = false),
    PORTRAIT(usesCamera2 = false, reportsProgress = false, showsProcessingOverlay = true),
    PANORAMA(usesCamera2 = false, reportsProgress = false, showsProcessingOverlay = false),
    NIGHT(usesCamera2 = true, reportsProgress = true, showsProcessingOverlay = true),
    LONG_EXPOSURE(usesCamera2 = true, reportsProgress = true, showsProcessingOverlay = true),
    HDR(usesCamera2 = true, reportsProgress = true, showsProcessingOverlay = true),
    /** Camera2 still with an optional DNG (RAW + JPEG). */
    RAW_STILL(usesCamera2 = true, reportsProgress = false, showsProcessingOverlay = false),
    VIDEO(usesCamera2 = false, reportsProgress = false, showsProcessingOverlay = false),
    SLOW_MO(usesCamera2 = true, reportsProgress = false, showsProcessingOverlay = false),
    TIME_LAPSE(usesCamera2 = true, reportsProgress = false, showsProcessingOverlay = false),
}

/**
 * What the current mode + camera + settings support. The single rule set shared by the engine
 * router (HybridCaptureEngine / Camera2Provider) and the UI (top bar, quick settings sheet,
 * shutter, manual controls), so a control is only offered where the capture path honors it.
 * Pure Kotlin so ModeCapabilitiesTest runs on the JVM.
 */
@Immutable
data class ModeCapabilities(
    val mode: CameraMode,
    val isFrontCamera: Boolean,
    /** Pipeline the next shutter press runs (depends on the live scene for Auto Night). */
    val pipeline: CapturePipeline,
    /** Flash settings offered here, in cycle order; empty = no flash control. */
    val lightModes: List<LightMode>,
    /** The flash control is a continuous torch (video modes): only Off / On. */
    val flashIsTorch: Boolean,
    /** Setting the flash control acts on right now ([lightModes] fallback for unsupported values). */
    val effectiveLightMode: LightMode?,
    /** Auto Night will take the next shot (moon on the shutter). */
    val autoNightActive: Boolean,
    /** HDR is enabled but paused because the flash will be used instead. */
    val hdrPausedByFlash: Boolean,
    /** The 12 MP / full-resolution chip changes the result. */
    val showsResolutionChip: Boolean,
    /** Photo-only rows in the quick settings sheet (HDR, resolution, file format). */
    val showsPhotoOutputSettings: Boolean,
    /**
     * Video resolution, frame rate, stabilization and audio apply (CameraX Video only; slow
     * motion and time lapse record silently at their own size and rate).
     */
    val showsVideoQualitySettings: Boolean,
    val showsMacro: Boolean,
    val supportsTimer: Boolean,
    /** Holding the shutter records (QuickTake in Photo/Portrait, hold-to-record in Video). */
    val supportsQuickTake: Boolean,
    /** A still can be grabbed while recording. */
    val supportsVideoSnapshot: Boolean,
    /** Manual ISO / shutter / focus / white balance are honored by this capture path. */
    val supportsManualControls: Boolean,
    /**
     * The adjustments tool row (Tune) opens here: everywhere manual controls work, plus Slow
     * motion and Time lapse, whose recordings honor exposure compensation (and Slow motion's
     * speed) but not manual ISO / shutter / focus / white balance (high-speed sessions require
     * auto 3A).
     */
    val offersAdjustments: Boolean,
    /** The slow-motion speed (capture rate) can be chosen. */
    val supportsSlowMotionSpeed: Boolean,
    /** Zoom can still change while recording (Camera2 recordings fix zoom at the start). */
    val supportsZoomWhileRecording: Boolean,
) {
    val isVideo: Boolean get() = CameraModes.isVideo(mode)

    /** Next value for the one-tap flash button, or null when there is no flash control. */
    fun nextLightMode(): LightMode? {
        val current = effectiveLightMode ?: return null
        val i = lightModes.indexOf(current)
        return lightModes[(i + 1) % lightModes.size]
    }

    companion object {
        private val FLASH_FIRES = setOf(LightMode.AUTO_FLASH, LightMode.FLASH)
        private val MANUAL_PIPELINES = setOf(
            CapturePipeline.PHOTO, CapturePipeline.RAW_STILL, CapturePipeline.PORTRAIT, CapturePipeline.VIDEO,
        )

        /**
         * @param sceneIsDark the low-light monitor's state (drives Auto Night).
         * @param manualExposure manual ISO or shutter is active; Auto Night then stays out of the way.
         */
        fun forMode(
            mode: CameraMode,
            isFrontCamera: Boolean,
            prefs: UserPreferences,
            sceneIsDark: Boolean = false,
            manualExposure: Boolean = false,
        ): ModeCapabilities {
            val rear = !isFrontCamera
            val pipeline = pipelineFor(mode, isFrontCamera, prefs, sceneIsDark, manualExposure)
            // Static routing (ignores the live scene) so controls don't flicker as light changes.
            val staticPipeline = pipelineFor(mode, isFrontCamera, prefs, sceneIsDark = false, manualExposure)

            val lightModes = when {
                !rear -> emptyList()
                CameraModes.isVideo(mode) -> listOf(LightMode.NONE, LightMode.FLASH)
                mode == CameraMode.PHOTO && staticPipeline != CapturePipeline.RAW_STILL -> LightMode.entries.toList()
                mode == CameraMode.PORTRAIT -> listOf(LightMode.NONE, LightMode.AUTO_FLASH, LightMode.FLASH)
                else -> emptyList()
            }
            val effectiveLightMode = when {
                lightModes.isEmpty() -> null
                prefs.lightMode in lightModes -> prefs.lightMode
                else -> LightMode.NONE
            }

            return ModeCapabilities(
                mode = mode,
                isFrontCamera = isFrontCamera,
                pipeline = pipeline,
                lightModes = lightModes,
                flashIsTorch = CameraModes.isVideo(mode),
                effectiveLightMode = effectiveLightMode,
                autoNightActive = mode == CameraMode.PHOTO && pipeline == CapturePipeline.NIGHT,
                hdrPausedByFlash = mode == CameraMode.PHOTO && rear && prefs.hdrPhoto &&
                    prefs.saveMode != ImageSaveMode.RAW_JPEG && prefs.lightMode in FLASH_FIRES,
                showsResolutionChip = mode == CameraMode.PHOTO && staticPipeline == CapturePipeline.PHOTO && rear,
                showsPhotoOutputSettings = mode == CameraMode.PHOTO && rear,
                showsVideoQualitySettings = mode == CameraMode.VIDEO,
                showsMacro = rear && (mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT),
                supportsTimer = !CameraModes.isVideo(mode) && mode != CameraMode.PANORAMA,
                supportsQuickTake = mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT || mode == CameraMode.VIDEO,
                supportsVideoSnapshot = mode == CameraMode.VIDEO,
                supportsManualControls = staticPipeline in MANUAL_PIPELINES,
                offersAdjustments = staticPipeline in MANUAL_PIPELINES ||
                    staticPipeline == CapturePipeline.SLOW_MO || staticPipeline == CapturePipeline.TIME_LAPSE,
                supportsSlowMotionSpeed = mode == CameraMode.SLOW_MO,
                supportsZoomWhileRecording = !CameraModes.isCamera2Video(mode),
            )
        }

        /**
         * Routing for a shutter press. Photo on the rear camera: RAW + JPEG first (single
         * Camera2 still), then Auto Night in a dark scene, then HDR - unless the flash will
         * fire (only CameraX fires it), then the CameraX photo. The front camera always uses
         * the CameraX photo in Photo mode.
         */
        fun pipelineFor(
            mode: CameraMode,
            isFrontCamera: Boolean,
            prefs: UserPreferences,
            sceneIsDark: Boolean = false,
            manualExposure: Boolean = false,
        ): CapturePipeline = when (mode) {
            CameraMode.NIGHT_SIGHT -> CapturePipeline.NIGHT
            CameraMode.LONG_EXPOSURE -> CapturePipeline.LONG_EXPOSURE
            CameraMode.PORTRAIT -> CapturePipeline.PORTRAIT
            CameraMode.PANORAMA -> CapturePipeline.PANORAMA
            CameraMode.VIDEO -> CapturePipeline.VIDEO
            CameraMode.SLOW_MO -> CapturePipeline.SLOW_MO
            CameraMode.TIME_LAPSE -> CapturePipeline.TIME_LAPSE
            CameraMode.PHOTO -> when {
                isFrontCamera -> CapturePipeline.PHOTO
                prefs.saveMode == ImageSaveMode.RAW_JPEG -> CapturePipeline.RAW_STILL
                prefs.lightMode == LightMode.AUTO_NIGHT_SIGHT && sceneIsDark && !manualExposure -> CapturePipeline.NIGHT
                prefs.hdrPhoto && prefs.lightMode !in FLASH_FIRES -> CapturePipeline.HDR
                else -> CapturePipeline.PHOTO
            }
        }
    }
}
