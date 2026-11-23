package com.fearmikey.camera.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.fearmikey.camera.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val SHOW_LEVELER = booleanPreferencesKey("show_leveler")
        val LOCATION_TAGGING = booleanPreferencesKey("location_tagging")
        /** Legacy boolean, only read to migrate to [VOLUME_KEY_ACTION]. */
        val VOLUME_KEY_SHUTTER = booleanPreferencesKey("volume_key_shutter")
        val TIMER = stringPreferencesKey("timer")
        val LIGHT_MODE = stringPreferencesKey("light_mode")
        /** Set once light_mode has been written with the 4-option meaning (see [LightMode]). */
        val LIGHT_MODE_V2 = booleanPreferencesKey("light_mode_v2")
        val MACRO_FOCUS = stringPreferencesKey("macro_focus")
        val RESOLUTION = stringPreferencesKey("resolution")
        val SAVE_MODE = stringPreferencesKey("save_mode")
        val GESTURE_TRIGGER = booleanPreferencesKey("gesture_trigger")
        val CAMERA_SOUNDS = booleanPreferencesKey("camera_sounds")
        val GRID_MODE = stringPreferencesKey("grid_mode")
        val MIRROR_SELFIES = booleanPreferencesKey("mirror_selfies")
        val TIMER_LIGHT = booleanPreferencesKey("timer_light")
        val VIDEO_STABILIZATION = booleanPreferencesKey("video_stabilization")
        val HDR_PHOTO = booleanPreferencesKey("hdr_photo")
        val VIDEO_FRAME_RATE = stringPreferencesKey("video_frame_rate")
        val RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val VOLUME_KEY_ACTION = stringPreferencesKey("volume_key_action")
        val SCAN_QR_CODES = booleanPreferencesKey("scan_qr_codes")
        val DOUBLE_TAP_SWITCH = booleanPreferencesKey("double_tap_switch")
        val LOW_LIGHT_SUGGESTION = booleanPreferencesKey("low_light_suggestion")
        val PORTRAIT_APERTURE = floatPreferencesKey("portrait_aperture")
        val PORTRAIT_KEEP_ORIGINAL = booleanPreferencesKey("portrait_keep_original")
        val SLOW_MOTION_FPS = intPreferencesKey("slow_motion_fps")
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            UserPreferences(
                aspectRatio = enumOr(preferences[PreferencesKeys.ASPECT_RATIO], AspectRatio.RATIO_4_3),
                videoResolution = enumOr(preferences[PreferencesKeys.VIDEO_RESOLUTION], VideoResolution.RES_1080P),
                showLeveler = preferences[PreferencesKeys.SHOW_LEVELER] ?: false,
                locationTagging = preferences[PreferencesKeys.LOCATION_TAGGING] ?: false,
                timer = enumOr(preferences[PreferencesKeys.TIMER], TimerMode.OFF),
                lightMode = enumOr(preferences[PreferencesKeys.LIGHT_MODE], LightMode.NONE).let {
                    // Older builds stored auto flash as AUTO_NIGHT_SIGHT.
                    if (it == LightMode.AUTO_NIGHT_SIGHT && preferences[PreferencesKeys.LIGHT_MODE_V2] != true) {
                        LightMode.AUTO_FLASH
                    } else {
                        it
                    }
                },
                // The old default AUTO behaved like Off; enumOr maps it (and anything unknown) to OFF.
                macroFocus = enumOr(preferences[PreferencesKeys.MACRO_FOCUS], MacroMode.OFF),
                resolution = enumOr(preferences[PreferencesKeys.RESOLUTION], ResolutionMode.MP_12),
                saveMode = enumOr(preferences[PreferencesKeys.SAVE_MODE], ImageSaveMode.JPEG),
                gestureTrigger = preferences[PreferencesKeys.GESTURE_TRIGGER] ?: false,
                cameraSounds = preferences[PreferencesKeys.CAMERA_SOUNDS] ?: true,
                gridMode = enumOr(preferences[PreferencesKeys.GRID_MODE], GridMode.OFF),
                mirrorSelfies = preferences[PreferencesKeys.MIRROR_SELFIES] ?: false,
                timerLight = preferences[PreferencesKeys.TIMER_LIGHT] ?: false,
                videoStabilization = preferences[PreferencesKeys.VIDEO_STABILIZATION] ?: true,
                hdrPhoto = preferences[PreferencesKeys.HDR_PHOTO] ?: false,
                videoFrameRate = enumOr(preferences[PreferencesKeys.VIDEO_FRAME_RATE], VideoFrameRate.AUTO),
                recordAudio = preferences[PreferencesKeys.RECORD_AUDIO] ?: true,
                // Migrates the old boolean: an explicit "off" maps to normal volume behavior.
                volumeKeyAction = enumOr(
                    preferences[PreferencesKeys.VOLUME_KEY_ACTION],
                    if (preferences[PreferencesKeys.VOLUME_KEY_SHUTTER] == false) VolumeKeyAction.VOLUME else VolumeKeyAction.SHUTTER
                ),
                scanQrCodes = preferences[PreferencesKeys.SCAN_QR_CODES] ?: true,
                doubleTapSwitch = preferences[PreferencesKeys.DOUBLE_TAP_SWITCH] ?: true,
                lowLightSuggestion = preferences[PreferencesKeys.LOW_LIGHT_SUGGESTION] ?: true,
                portraitAperture = PortraitAperture.nearest(
                    preferences[PreferencesKeys.PORTRAIT_APERTURE] ?: PortraitAperture.DEFAULT
                ),
                portraitKeepOriginal = preferences[PreferencesKeys.PORTRAIT_KEEP_ORIGINAL] ?: false,
                slowMotionFps = preferences[PreferencesKeys.SLOW_MOTION_FPS] ?: SlowMotion.DEFAULT_FPS,
            )
        }

    suspend fun updateAspectRatio(aspectRatio: AspectRatio) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASPECT_RATIO] = aspectRatio.name
        }
    }

    suspend fun updateVideoResolution(resolution: VideoResolution) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VIDEO_RESOLUTION] = resolution.name
        }
    }

    suspend fun updateLocationTagging(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCATION_TAGGING] = enabled
        }
    }

    suspend fun updateShowLeveler(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_LEVELER] = show
        }
    }

    suspend fun updateTimer(timer: TimerMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TIMER] = timer.name
        }
    }

    suspend fun updateLightMode(mode: LightMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIGHT_MODE] = mode.name
            preferences[PreferencesKeys.LIGHT_MODE_V2] = true
        }
    }

    suspend fun updateMacroFocus(mode: MacroMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MACRO_FOCUS] = mode.name
        }
    }

    suspend fun updateResolution(resolution: ResolutionMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RESOLUTION] = resolution.name
        }
    }

    suspend fun updateSaveMode(saveMode: ImageSaveMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVE_MODE] = saveMode.name
        }
    }

    suspend fun updateGestureTrigger(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GESTURE_TRIGGER] = enabled
        }
    }

    suspend fun updateCameraSounds(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CAMERA_SOUNDS] = enabled
        }
    }

    suspend fun updateGridMode(mode: GridMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GRID_MODE] = mode.name
        }
    }

    suspend fun updateMirrorSelfies(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIRROR_SELFIES] = enabled
        }
    }

    suspend fun updateTimerLight(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TIMER_LIGHT] = enabled
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun updateHdrPhoto(enabled: Boolean) = put(PreferencesKeys.HDR_PHOTO, enabled)
    suspend fun updateVideoFrameRate(rate: VideoFrameRate) = put(PreferencesKeys.VIDEO_FRAME_RATE, rate.name)
    suspend fun updateRecordAudio(enabled: Boolean) = put(PreferencesKeys.RECORD_AUDIO, enabled)
    suspend fun updateVolumeKeyAction(action: VolumeKeyAction) = put(PreferencesKeys.VOLUME_KEY_ACTION, action.name)
    suspend fun updateScanQrCodes(enabled: Boolean) = put(PreferencesKeys.SCAN_QR_CODES, enabled)
    suspend fun updateDoubleTapSwitch(enabled: Boolean) = put(PreferencesKeys.DOUBLE_TAP_SWITCH, enabled)
    suspend fun updateLowLightSuggestion(enabled: Boolean) = put(PreferencesKeys.LOW_LIGHT_SUGGESTION, enabled)
    suspend fun updatePortraitAperture(fNumber: Float) =
        put(PreferencesKeys.PORTRAIT_APERTURE, PortraitAperture.nearest(fNumber))
    suspend fun updatePortraitKeepOriginal(enabled: Boolean) = put(PreferencesKeys.PORTRAIT_KEEP_ORIGINAL, enabled)
    suspend fun updateSlowMotionFps(fps: Int) = put(PreferencesKeys.SLOW_MOTION_FPS, fps)

    suspend fun updateVideoStabilization(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VIDEO_STABILIZATION] = enabled
        }
    }
}
