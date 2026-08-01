package com.fearmikey.camera.ui.camera

import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fearmikey.camera.data.engine.CaptureEngine
import com.fearmikey.camera.data.repository.MediaRepository
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.domain.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CameraViewModel(
    private val captureEngine: CaptureEngine,
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel(), SensorEventListener {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    
    // Low-pass filter variables
    private var currentTilt = 0f
    private val alpha = 0.15f // Smoothing factor
    
    private var focusJob: Job? = null

    init {
        refreshThumbnail()
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { prefs ->
                _uiState.update { it.copy(userPreferences = prefs) }
                captureEngine.applySettings(prefs)
            }
        }
    }

    fun updateAspectRatio(aspectRatio: AspectRatio) {
        viewModelScope.launch { userPreferencesRepository.updateAspectRatio(aspectRatio) }
    }

    fun updateVideoResolution(resolution: VideoResolution) {
        viewModelScope.launch { userPreferencesRepository.updateVideoResolution(resolution) }
    }

    fun updateImageFormat(format: ImageFormat) {
        viewModelScope.launch { userPreferencesRepository.updateImageFormat(format) }
    }

    fun updateVideoCodec(codec: VideoCodec) {
        viewModelScope.launch { userPreferencesRepository.updateVideoCodec(codec) }
    }

    fun toggleGridLines() {
        viewModelScope.launch {
            userPreferencesRepository.updateShowGridLines(!_uiState.value.userPreferences.showGridLines)
        }
    }

    fun toggleLeveler() {
        viewModelScope.launch {
            userPreferencesRepository.updateShowLeveler(!_uiState.value.userPreferences.showLeveler)
        }
    }

    fun toggleLocationTagging() {
        viewModelScope.launch {
            userPreferencesRepository.updateLocationTagging(!_uiState.value.userPreferences.locationTagging)
        }
    }

    fun setShowSettingsSheet(show: Boolean) {
        _uiState.update { it.copy(showSettingsSheet = show) }
    }

    fun bindToLifecycle(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider)
        _uiState.update { it.copy(capabilities = captureEngine.getCapabilities()) }
    }

    fun setMode(mode: CameraMode) {
        _uiState.update { it.copy(currentMode = mode) }
        captureEngine.setMode(mode)
    }

    fun takePicture() {
        _uiState.update { it.copy(isProcessing = true) }
        captureEngine.capturePhoto(
            onSuccess = { path ->
                val uri = if (path.startsWith("/")) Uri.fromFile(java.io.File(path)) else Uri.parse(path)
                _uiState.update { it.copy(lastCapturedUri = uri, isProcessing = false) }
                // Don't call refreshThumbnail immediately as MediaStore might not be ready
            },
            onError = { error ->
                error.printStackTrace()
                _uiState.update { it.copy(isProcessing = false) }
            }
        )
    }

    fun toggleCapture() {
        val state = _uiState.value
        if (state.currentMode == CameraMode.VIDEO || state.currentMode == CameraMode.SLOW_MO) {
            if (state.isRecording) {
                stopVideo()
            } else {
                startVideo()
            }
        } else {
            takePicture()
        }
    }

    fun startVideo() {
        _uiState.update { it.copy(isRecording = true) }
        captureEngine.startSlowMoVideo(
            onSuccess = { path ->
                val uri = if (path.startsWith("/")) Uri.fromFile(java.io.File(path)) else Uri.parse(path)
                _uiState.update { it.copy(lastCapturedUri = uri, isRecording = false) }
            },
            onError = { error ->
                error.printStackTrace()
                _uiState.update { it.copy(isRecording = false) }
            }
        )
    }

    fun stopVideo() {
        _uiState.update { it.copy(isRecording = false) }
        captureEngine.stopSlowMoVideo()
        refreshThumbnail()
    }

    fun quickTakeStart() {
        _uiState.update { it.copy(isRecording = true) }
        // In a real app, Quick Take might use a different capture request template
        // but here we reuse the video logic
        startVideo()
    }

    fun quickTakeStop() {
        stopVideo()
    }

    fun setZoom(ratio: Float) {
        val clampedRatio = ratio.coerceIn(1.0f, 10.0f) // Assuming 10x max zoom
        _uiState.update { it.copy(zoomLevel = clampedRatio) }
        captureEngine.setZoom(clampedRatio)
    }

    fun refreshThumbnail() {
        viewModelScope.launch {
            val uri = mediaRepository.getLatestMediaUri()
            _uiState.update { it.copy(lastCapturedUri = uri) }
        }
    }

    fun toggleProMode() {
        _uiState.update { 
            val newState = !it.isProMode
            captureEngine.setProMode(newState)
            if (newState) {
                captureEngine.updateManualConfig(it.proConfig)
            }
            it.copy(isProMode = newState)
        }
    }

    fun updateProConfig(config: ProConfig) {
        _uiState.update { it.copy(proConfig = config) }
        captureEngine.updateManualConfig(config)
    }

    fun focusAt(x: Float, y: Float) {
        captureEngine.focusAt(x, y)
        _uiState.update { it.copy(focusPoint = androidx.compose.ui.geometry.Offset(x, y)) }
        
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(focusPoint = null) }
        }
    }

    fun initSensors(sensorManager: SensorManager) {
        this.sensorManager = sensorManager
        this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        if (uiState.value.userPreferences.showLeveler) {
            startLeveler()
        }
    }

    private fun startLeveler() {
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopLeveler() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            
            // Apply low-pass filter
            currentTilt = currentTilt + alpha * (roll - currentTilt)
            
            _uiState.update { it.copy(horizonTilt = currentTilt) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        stopLeveler()
    }
}
