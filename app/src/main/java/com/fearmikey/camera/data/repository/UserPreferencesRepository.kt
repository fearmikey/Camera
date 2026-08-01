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
                    preferences[PreferencesKeys.ASPECT_RATIO] ?: AspectRatio.RATIO_4_3.name
                ),
                videoResolution = VideoResolution.valueOf(
                    preferences[PreferencesKeys.VIDEO_RESOLUTION] ?: VideoResolution.RES_1080P.name
                ),
                imageFormat = ImageFormat.valueOf(
                    preferences[PreferencesKeys.IMAGE_FORMAT] ?: ImageFormat.JPEG.name
                ),
                videoCodec = VideoCodec.valueOf(
                    preferences[PreferencesKeys.VIDEO_CODEC] ?: VideoCodec.H264.name
                ),
                showGridLines = preferences[PreferencesKeys.SHOW_GRID_LINES] ?: false,
                showLeveler = preferences[PreferencesKeys.SHOW_LEVELER] ?: false,
                locationTagging = preferences[PreferencesKeys.LOCATION_TAGGING] ?: false,
                saveProSettings = preferences[PreferencesKeys.SAVE_PRO_SETTINGS] ?: false
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

    suspend fun updateShowGridLines(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_GRID_LINES] = show
        }
    }

    suspend fun updateShowLeveler(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_LEVELER] = show
        }
    }

    suspend fun updateLocationTagging(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCATION_TAGGING] = enabled
        }
    }

    suspend fun updateSaveProSettings(save: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVE_PRO_SETTINGS] = save
        }
    }
}
