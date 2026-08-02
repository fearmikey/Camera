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
        val IMAGE_FORMAT = stringPreferencesKey("image_format")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
        val SHOW_LEVELER = booleanPreferencesKey("show_leveler")
        val LOCATION_TAGGING = booleanPreferencesKey("location_tagging")
        val SAVE_PRO_SETTINGS = booleanPreferencesKey("save_pro_settings")
        val FLASH_MODE = stringPreferencesKey("flash_mode")
        val VOLUME_KEY_SHUTTER = booleanPreferencesKey("volume_key_shutter")
        val TIMER = stringPreferencesKey("timer")
        val LIGHT_MODE = stringPreferencesKey("light_mode")
        val MACRO_FOCUS = stringPreferencesKey("macro_focus")
        val TOP_SHOT = booleanPreferencesKey("top_shot")
        val MOTION_PHOTO = stringPreferencesKey("motion_photo")
        val RESOLUTION = stringPreferencesKey("resolution")
        val SAVE_MODE = stringPreferencesKey("save_mode")
        val LENS_SELECTION = stringPreferencesKey("lens_selection")
        val GESTURE_TRIGGER = booleanPreferencesKey("gesture_trigger")
        val CAMERA_SOUNDS = booleanPreferencesKey("camera_sounds")
        val GRID_MODE = stringPreferencesKey("grid_mode")
        val MIRROR_SELFIES = booleanPreferencesKey("mirror_selfies")
        val ULTRA_HDR = booleanPreferencesKey("ultra_hdr")
        val TIMER_LIGHT = booleanPreferencesKey("timer_light")
    }

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
                aspectRatio = AspectRatio.valueOf(
                    preferences[PreferencesKeys.ASPECT_RATIO] ?: AspectRatio.RATIO_4_3.name,
                ),
                videoResolution = VideoResolution.valueOf(
                    preferences[PreferencesKeys.VIDEO_RESOLUTION] ?: VideoResolution.RES_1080P.name,
                ),
                imageFormat = ImageFormat.valueOf(
                    preferences[PreferencesKeys.IMAGE_FORMAT] ?: ImageFormat.JPEG.name,
                ),
                videoCodec = VideoCodec.valueOf(
                    preferences[PreferencesKeys.VIDEO_CODEC] ?: VideoCodec.H264.name,
                ),
                showGridLines = preferences[PreferencesKeys.SHOW_GRID_LINES] ?: false,
                showLeveler = preferences[PreferencesKeys.SHOW_LEVELER] ?: false,
                locationTagging = preferences[PreferencesKeys.LOCATION_TAGGING] ?: false,
                saveProSettings = preferences[PreferencesKeys.SAVE_PRO_SETTINGS] ?: false,
                flashMode = FlashMode.valueOf(
                    preferences[PreferencesKeys.FLASH_MODE] ?: FlashMode.OFF.name
                ),
                volumeKeyShutter = preferences[PreferencesKeys.VOLUME_KEY_SHUTTER] ?: false,
                timer = TimerMode.valueOf(
                    preferences[PreferencesKeys.TIMER] ?: TimerMode.OFF.name
                ),
                lightMode = LightMode.valueOf(
                    preferences[PreferencesKeys.LIGHT_MODE] ?: LightMode.AUTO_NIGHT_SIGHT.name
                ),
                macroFocus = MacroMode.valueOf(
                    preferences[PreferencesKeys.MACRO_FOCUS] ?: MacroMode.AUTO.name
                ),
                topShot = preferences[PreferencesKeys.TOP_SHOT] ?: false,
                motionPhoto = MotionPhotoMode.valueOf(
                    preferences[PreferencesKeys.MOTION_PHOTO] ?: MotionPhotoMode.AUTO.name
                ),
                resolution = ResolutionMode.valueOf(
                    preferences[PreferencesKeys.RESOLUTION] ?: ResolutionMode.MP_12.name
                ),
                saveMode = ImageSaveMode.valueOf(
                    preferences[PreferencesKeys.SAVE_MODE] ?: ImageSaveMode.JPEG.name
                ),
                lensSelection = LensSelectionMode.valueOf(
                    preferences[PreferencesKeys.LENS_SELECTION] ?: LensSelectionMode.AUTO.name
                ),
                gestureTrigger = preferences[PreferencesKeys.GESTURE_TRIGGER] ?: false,
                cameraSounds = preferences[PreferencesKeys.CAMERA_SOUNDS] ?: true,
                gridMode = GridMode.valueOf(
                    preferences[PreferencesKeys.GRID_MODE] ?: GridMode.OFF.name,
                ),
                mirrorSelfies = preferences[PreferencesKeys.MIRROR_SELFIES] ?: false,
                ultraHdr = preferences[PreferencesKeys.ULTRA_HDR] ?: true,
                timerLight = preferences[PreferencesKeys.TIMER_LIGHT] ?: false,
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

    suspend fun updateImageFormat(format: ImageFormat) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMAGE_FORMAT] = format.name
        }
    }

    suspend fun updateVideoCodec(codec: VideoCodec) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VIDEO_CODEC] = codec.name
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

    suspend fun updateFlashMode(flashMode: FlashMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FLASH_MODE] = flashMode.name
        }
    }

    suspend fun updateVolumeKeyShutter(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOLUME_KEY_SHUTTER] = enabled
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
        }
    }

    suspend fun updateMacroFocus(mode: MacroMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MACRO_FOCUS] = mode.name
        }
    }

    suspend fun updateTopShot(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOP_SHOT] = enabled
        }
    }

    suspend fun updateMotionPhoto(mode: MotionPhotoMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOTION_PHOTO] = mode.name
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

    suspend fun updateLensSelection(mode: LensSelectionMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LENS_SELECTION] = mode.name
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

    suspend fun updateUltraHdr(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRA_HDR] = enabled
        }
    }

    suspend fun updateTimerLight(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TIMER_LIGHT] = enabled
        }
    }
}
