package com.fearmikey.camera.domain.model

enum class CameraMode {
    PHOTO,
    VIDEO,
    NIGHT_SIGHT,
    // HDR is not a mode: PHOTO uses the HDR pipeline when UserPreferences.hdrPhoto is on.
    SLOW_MO,
    PORTRAIT,
    PANORAMA,
    /** Aligned multi-frame average that blurs moving subjects (waterfalls, traffic). */
    LONG_EXPOSURE,
    /** Camera2 MediaRecorder recording at a low capture rate, played back at 30 fps. */
    TIME_LAPSE,
}

/** Mode rows shown under the shutter. Single source of truth for the UI. */
object CameraModes {
    val photo = listOf(CameraMode.LONG_EXPOSURE, CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.NIGHT_SIGHT, CameraMode.PANORAMA)
    val video = listOf(CameraMode.SLOW_MO, CameraMode.VIDEO, CameraMode.TIME_LAPSE)

    fun label(mode: CameraMode): String = when (mode) {
        CameraMode.PHOTO -> "Photo"
        CameraMode.VIDEO -> "Video"
        CameraMode.NIGHT_SIGHT -> "Night"
        CameraMode.SLOW_MO -> "Slow motion"
        CameraMode.PORTRAIT -> "Portrait"
        CameraMode.PANORAMA -> "Panorama"
        CameraMode.LONG_EXPOSURE -> "Long exposure"
        CameraMode.TIME_LAPSE -> "Time lapse"
    }

    /** Modes recorded with Camera2 + MediaRecorder (no live preview while recording). */
    fun isCamera2Video(mode: CameraMode) = mode == CameraMode.SLOW_MO || mode == CameraMode.TIME_LAPSE

    fun isVideo(mode: CameraMode) = mode == CameraMode.VIDEO || isCamera2Video(mode)
}
