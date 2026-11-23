package com.fearmikey.camera.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class UserPreferences(
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val videoResolution: VideoResolution = VideoResolution.RES_1080P,
    /** "Framing hints": horizon and top-down levels. */
    val showLeveler: Boolean = false,
    val locationTagging: Boolean = false,
    val cameraSounds: Boolean = true,
    val gridMode: GridMode = GridMode.OFF,
    val mirrorSelfies: Boolean = false,
    val timerLight: Boolean = false,
    val timer: TimerMode = TimerMode.OFF,
    val lightMode: LightMode = LightMode.NONE,
    val macroFocus: MacroMode = MacroMode.OFF,
    val resolution: ResolutionMode = ResolutionMode.MP_12,
    val saveMode: ImageSaveMode = ImageSaveMode.JPEG,
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
    /** Portrait mode's simulated aperture (f-number, see [PortraitAperture]). */
    val portraitAperture: Float = PortraitAperture.DEFAULT,
    /** Portrait mode also saves the unprocessed photo. */
    val portraitKeepOriginal: Boolean = false,
) {
    /** Flash behavior for CameraX captures (Auto Night never fires the flash). */
    val effectiveFlashMode: FlashMode
        get() = when (lightMode) {
            LightMode.NONE, LightMode.AUTO_NIGHT_SIGHT -> FlashMode.OFF
            LightMode.AUTO_FLASH -> FlashMode.AUTO
            LightMode.FLASH -> FlashMode.ON
        }
    // Which pipeline a shot uses (Auto Night, HDR, RAW, flash) is decided by ModeCapabilities.
}

/**
 * Simulated aperture for Portrait mode. Smaller f-numbers blur the background more;
 * the blur strength is relative to f/2.8 (the default).
 */
object PortraitAperture {
    val stops = listOf(1.4f, 1.8f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
    const val DEFAULT = 2.8f

    /** Closest offered stop (stored values are snapped so the slider always lines up). */
    fun nearest(fNumber: Float): Float = stops.minBy { kotlin.math.abs(it - fNumber) }

    /** Native blur strength: 1.0 at f/2.8, 2.0 at f/1.4, ~0.18 at f/16. */
    fun blurStrength(fNumber: Float): Float = DEFAULT / nearest(fNumber)

    fun label(fNumber: Float): String {
        val f = nearest(fNumber)
        val number = if (f % 1f == 0f) f.toInt().toString() else String.format(java.util.Locale.US, "%.1f", f)
        return "ƒ/$number"
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

/**
 * Flash setting (Photo). Names are persisted. In 1.1.0 and earlier AUTO_NIGHT_SIGHT meant auto
 * flash; UserPreferencesRepository migrates such stored values to AUTO_FLASH once.
 */
enum class LightMode(val label: String) {
    NONE("Off"),
    /** No flash; dark scenes are shot with the Night pipeline automatically. */
    AUTO_NIGHT_SIGHT("Auto Night"),
    AUTO_FLASH("Auto flash"),
    FLASH("On")
}

/** Persisted by name. Builds before the settings cleanup also stored AUTO, which loads as OFF. */
enum class MacroMode {
    OFF, ON
}

/** Persisted by name; [label] is shared by the quick-settings sheet and SettingsActivity. */
enum class ResolutionMode(val label: String) {
    MP_12("12 MP (binned)"), MP_50("Full resolution")
}

/** Persisted by name; [label] is shared by the quick-settings sheet and SettingsActivity. */
enum class ImageSaveMode(val label: String) {
    JPEG("JPEG"), RAW_JPEG("RAW + JPEG")
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
