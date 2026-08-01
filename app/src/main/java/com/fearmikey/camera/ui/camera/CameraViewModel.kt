package com.fearmikey.camera.ui.camera

import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.CameraSelector
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
import kotlin.math.abs

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
    private val alpha = 0.3f // Increased for more responsiveness
    
    private var focusJob: Job? = null

    init {
        refreshThumbnail()
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { prefs ->
                val prevShowLeveler = _uiState.value.userPreferences.showLeveler
                _uiState.update { it.copy(userPreferences = prefs) }
                captureEngine.applySettings(prefs)
                
                if (prefs.showLeveler && !prevShowLeveler) {
                    startLeveler()
                } else if (!prefs.showLeveler && prevShowLeveler) {
                    stopLeveler()
                }
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

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    fun bindToLifecycle(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider
        
        val selector = if (_uiState.value.isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector)
        val capabilities = captureEngine.getCapabilities()
        _uiState.update { it.copy(
            capabilities = capabilities,
            zoomPresets = capabilities?.zoomPresets ?: listOf(1.0f)
        ) }
    }

    fun toggleCamera() {
        val nextIsFront = !_uiState.value.isFrontCamera
        _uiState.update { it.copy(isFrontCamera = nextIsFront) }
        
        val lifecycleOwner = currentLifecycleOwner
        val surfaceProvider = currentSurfaceProvider
        
        if (lifecycleOwner != null && surfaceProvider != null) {
            val selector = if (nextIsFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector)
        }
    }

    fun setMode(mode: CameraMode) {
        _uiState.update { it.copy(currentMode = mode) }
        captureEngine.setMode(mode)
    }

    fun takePicture() {
        _uiState.update { it.copy(isProcessing = true) }
        captureEngine.capturePhoto(
            onSuccess = { uriString ->
                val uri = Uri.parse(uriString)
                _uiState.update { it.copy(lastCapturedUri = uri, isProcessing = false) }
                // Delay refresh to allow MediaStore to index the new file
                viewModelScope.launch {
                    delay(500)
                    refreshThumbnail()
                }
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
            onSuccess = {
                // Recording actually started
            },
            onError = { error ->
                error.printStackTrace()
                _uiState.update { it.copy(isRecording = false) }
            }
        )
    }

    fun stopVideo() {
        captureEngine.stopSlowMoVideo(
            onSuccess = { uriString ->
                val uri = Uri.parse(uriString)
                _uiState.update { it.copy(lastCapturedUri = uri, isRecording = false) }
                viewModelScope.launch {
                    delay(800) // Slightly longer delay for video indexing
                    refreshThumbnail()
                }
            }
        )
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
        val clampedRatio = ratio.coerceIn(0.5f, 10.0f)
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
        // Fallback to accelerometer if rotation vector is not available
        if (this.rotationSensor == null) {
            this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        
        startLeveler()
    }

    private fun startLeveler() {
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopLeveler() {
        // We only stop if we're not using orientation for icons either
        // But for this requirement, we want it always
        // sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            
            updateOrientationFromRoll(roll)

            if (abs(pitch) < 75) {
                currentTilt = currentTilt + alpha * (roll - currentTilt)
                _uiState.update { it.copy(horizonTilt = currentTilt) }
            }
        } else if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val roll = Math.toDegrees(Math.atan2(-ax.toDouble(), ay.toDouble())).toFloat()
            
            updateOrientationFromRoll(roll)

            currentTilt = currentTilt + alpha * (roll - currentTilt)
            _uiState.update { it.copy(horizonTilt = currentTilt) }
        }
    }

    private fun updateOrientationFromRoll(roll: Float) {
        val newOrientation = when {
            roll > -45 && roll <= 45 -> 0
            roll > 45 && roll <= 135 -> 270
            roll > -135 && roll <= -45 -> 90
            else -> 180
        }
        
        val isLandscape = newOrientation == 90 || newOrientation == 270
        
        if (newOrientation != _uiState.value.orientation || isLandscape != _uiState.value.autoShowLeveler) {
            _uiState.update { it.copy(
                orientation = newOrientation,
                autoShowLeveler = isLandscape
            ) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        stopLeveler()
    }
}
