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
    val lightMode: LightMode = LightMode.AUTO_NIGHT_SIGHT,
    val macroFocus: MacroMode = MacroMode.AUTO,
    val topShot: Boolean = false,
    val motionPhoto: MotionPhotoMode = MotionPhotoMode.AUTO,
    val resolution: ResolutionMode = ResolutionMode.MP_12,
    val saveMode: ImageSaveMode = ImageSaveMode.JPEG,
    val lensSelection: LensSelectionMode = LensSelectionMode.AUTO,
    val gestureTrigger: Boolean = true,
)

enum class TimerMode(val seconds: Int) {
    OFF(0), SEC_3(3), SEC_5(5), SEC_10(10)
}

enum class LightMode {
    NONE, AUTO_NIGHT_SIGHT, FLASH
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
