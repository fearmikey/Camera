package com.fearmikey.camera.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class UserPreferences(
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val videoResolution: VideoResolution = VideoResolution.RES_1080P,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val showGridLines: Boolean = false,
    val showLeveler: Boolean = false,
    val locationTagging: Boolean = false,
    val saveProSettings: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF,
    val volumeKeyShutter: Boolean = false,
    val cameraSounds: Boolean = true,
    val gridMode: GridMode = GridMode.OFF,
    val mirrorSelfies: Boolean = false,
    val ultraHdr: Boolean = true,
    val timerLight: Boolean = false,
    val timer: TimerMode = TimerMode.OFF,
    val lightMode: LightMode = LightMode.NONE,
    val macroFocus: MacroMode = MacroMode.AUTO,
    val topShot: Boolean = false,
    val motionPhoto: MotionPhotoMode = MotionPhotoMode.AUTO,
    val resolution: ResolutionMode = ResolutionMode.MP_12,
    val saveMode: ImageSaveMode = ImageSaveMode.JPEG,
    val lensSelection: LensSelectionMode = LensSelectionMode.AUTO,
    val gestureTrigger: Boolean = false,
    val videoStabilization: Boolean = true,
    /** PHOTO mode captures an exposure bracket and fuses it (Camera2 HDR pipeline). */
    val hdrPhoto: Boolean = false,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.AUTO,
    val recordAudio: Boolean = true,
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SHUTTER,
    val scanQrCodes: Boolean = true,
    val doubleTapSwitch: Boolean = true,
    val lowLightSuggestion: Boolean = true,
) {
    /** Flash behaviour selected in the quick settings sheet (Off / Auto / On). */
    val effectiveFlashMode: FlashMode
        get() = when (lightMode) {
            LightMode.NONE -> FlashMode.OFF
            LightMode.AUTO_NIGHT_SIGHT -> FlashMode.AUTO
            LightMode.FLASH -> FlashMode.ON
        }
}

enum class TimerMode(val seconds: Int) {
    OFF(0), SEC_3(3), SEC_5(5), SEC_10(10);

    companion object {
        /** Options offered in the UI (SEC_5 is kept only so older saved values still load). */
        val selectable = listOf(OFF, SEC_3, SEC_10)
    }
}

enum class VideoFrameRate(val label: String, val fps: Int?) {
    AUTO("Auto", null), FPS_30("30 fps", 30), FPS_60("60 fps", 60)
}

enum class VolumeKeyAction(val label: String) {
    SHUTTER("Shutter"), ZOOM("Zoom"), VOLUME("Volume")
}

/** Flash setting. Names are persisted, so they are kept for compatibility: NONE=Off, AUTO_NIGHT_SIGHT=Auto, FLASH=On. */
enum class LightMode(val label: String) {
    NONE("Off"), AUTO_NIGHT_SIGHT("Auto"), FLASH("On")
}

enum class MacroMode {
    OFF, AUTO, ON
}

enum class MotionPhotoMode {
    OFF, AUTO, ON
}

enum class ResolutionMode {
    MP_12, MP_50
}

enum class ImageSaveMode {
    JPEG, RAW_JPEG
}

enum class LensSelectionMode {
    AUTO, MANUAL
}

enum class FlashMode {
    OFF, ON, AUTO
}

enum class GridMode(val label: String) {
    OFF("No grid"),
    GRID_3X3("3 x 3"),
    GRID_4X4("4 x 4"),
    GOLDEN_RATIO("Golden ratio")
}

enum class AspectRatio(val value: String) {
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9"),
    RATIO_FULL("Full")
}

enum class VideoResolution(val value: String) {
    RES_1080P("1080p"),
    RES_4K("4K")
}

enum class ImageFormat(val value: String) {
    JPEG("JPEG"),
    HEIF("HEIF")
}

enum class VideoCodec(val value: String) {
    H264("H.264"),
    HEVC("HEVC"),
    AV1("AV1")
}
