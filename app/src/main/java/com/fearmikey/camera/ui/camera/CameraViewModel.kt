package com.fearmikey.camera.ui.camera

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.fearmikey.camera.data.engine.CaptureEngine
import com.fearmikey.camera.data.repository.MediaRepository
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.domain.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class CameraViewModel(
    private val captureEngine: CaptureEngine,
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel(), SensorEventListener {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    
    // Low-pass filter variables
    private var currentTilt = 0f
    private val alpha = 0.3f // Increased for more responsiveness
    
    private var focusJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var timerJob: Job? = null
    private var secondsRecorded = 0L

    init {
        refreshThumbnail()
        observePreferences()
        setupGestureTrigger()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { prefs ->
                val prevShowLeveler = _uiState.value.userPreferences.showLeveler
                _uiState.update { it.copy(
                    userPreferences = prefs,
                    flashMode = prefs.flashMode
                ) }
                captureEngine.applySettings(prefs)
                captureEngine.setFlashMode(prefs.flashMode)
                captureEngine.setGestureTriggerEnabled(prefs.gestureTrigger)
                
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

    fun updateTimer(timer: TimerMode) {
        viewModelScope.launch { userPreferencesRepository.updateTimer(timer) }
    }

    fun updateLightMode(mode: LightMode) {
        viewModelScope.launch { userPreferencesRepository.updateLightMode(mode) }
    }

    fun updateMacroFocus(mode: MacroMode) {
        viewModelScope.launch { userPreferencesRepository.updateMacroFocus(mode) }
    }

    fun updateTopShot(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.updateTopShot(enabled) }
    }

    fun updateMotionPhoto(mode: MotionPhotoMode) {
        viewModelScope.launch { userPreferencesRepository.updateMotionPhoto(mode) }
    }

    fun updateResolution(resolution: ResolutionMode) {
        viewModelScope.launch { userPreferencesRepository.updateResolution(resolution) }
    }

    fun updateSaveMode(saveMode: ImageSaveMode) {
        viewModelScope.launch { userPreferencesRepository.updateSaveMode(saveMode) }
    }

    fun updateLensSelection(mode: LensSelectionMode) {
        viewModelScope.launch { userPreferencesRepository.updateLensSelection(mode) }
    }

    fun toggleGridLines() {
        viewModelScope.launch {
            val currentMode = _uiState.value.userPreferences.gridMode
            val nextMode = when (currentMode) {
                GridMode.OFF -> GridMode.GRID_3X3
                GridMode.GRID_3X3 -> GridMode.OFF
                else -> GridMode.OFF
            }
            userPreferencesRepository.updateGridMode(nextMode)
        }
    }

    fun toggleLeveler() {
        viewModelScope.launch {
            userPreferencesRepository.updateShowLeveler(!_uiState.value.userPreferences.showLeveler)
        }
    }

    fun toggleFlashMode() {
        viewModelScope.launch {
            val nextMode = when (_uiState.value.flashMode) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.OFF
            }
            userPreferencesRepository.updateFlashMode(nextMode)
        }
    }

    fun toggleVolumeKeyShutter() {
        viewModelScope.launch {
            userPreferencesRepository.updateVolumeKeyShutter(!_uiState.value.userPreferences.volumeKeyShutter)
        }
    }

    fun toggleLocationTagging() {
        viewModelScope.launch {
            userPreferencesRepository.updateLocationTagging(!_uiState.value.userPreferences.locationTagging)
        }
    }

    fun toggleGestureTrigger() {
        viewModelScope.launch {
            userPreferencesRepository.updateGestureTrigger(!_uiState.value.userPreferences.gestureTrigger)
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

    fun setCaptureType(type: CaptureType) {
        _uiState.update { 
            val nextMode = if (type == CaptureType.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO
            it.copy(captureType = type, currentMode = nextMode) 
        }
        captureEngine.setMode(if (type == CaptureType.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO)
    }

    fun setMode(mode: CameraMode) {
        _uiState.update { it.copy(currentMode = mode) }
        captureEngine.setMode(mode)
    }

    fun takePicture() {
        if (timerJob != null) {
            cancelTimer()
            return
        }

        val timerMode = _uiState.value.userPreferences.timer
        if (timerMode != TimerMode.OFF) {
            startTimer(timerMode.seconds)
        } else {
            performCapture()
        }
    }

    private fun startTimer(seconds: Int) {
        timerJob?.cancel()
        _uiState.update { it.copy(timerCountdown = seconds) }
        timerJob = viewModelScope.launch {
            var remaining = seconds
            val timerLightEnabled = _uiState.value.userPreferences.timerLight
            while (remaining > 0) {
                captureEngine.playTimerTickSound()
                if (timerLightEnabled) {
                    captureEngine.setTorchEnabled(enabled = true)
                    delay(100.milliseconds)
                    captureEngine.setTorchEnabled(enabled = false)
                    delay(900.milliseconds)
                } else {
                    delay(1000.milliseconds)
                }
                remaining--
                _uiState.update { it.copy(timerCountdown = if (remaining > 0) remaining else null) }
            }
            timerJob = null
            performCapture()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { it.copy(timerCountdown = null) }
    }

    private fun performCapture() {
        _uiState.update { it.copy(isProcessing = true) }
        captureEngine.playShutterSound()
        captureEngine.capturePhoto(
            onSuccess = { uriString ->
                val uri = uriString.toUri()
                _uiState.update { it.copy(lastCapturedUri = uri, isProcessing = false) }
                // Delay refresh to allow MediaStore to index the new file
                viewModelScope.launch {
                    delay(500.milliseconds)
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
        _uiState.update { it.copy(isRecording = true, isPaused = false, recordingDuration = "00:00") }
        secondsRecorded = 0
        startRecordingTimer()
        captureEngine.playStartRecordingSound()
        captureEngine.startSlowMoVideo(
            onSuccess = {
                // Recording actually started
            },
            onError = { error ->
                error.printStackTrace()
                stopRecordingTimer()
                _uiState.update { it.copy(isRecording = false) }
            }
        )
    }

    fun stopVideo() {
        stopRecordingTimer()
        captureEngine.playStopRecordingSound()
        captureEngine.stopSlowMoVideo { uriString ->
            val uri = uriString.toUri()
            _uiState.update { it.copy(lastCapturedUri = uri, isRecording = false, isPaused = false) }
            viewModelScope.launch {
                delay(800.milliseconds) // Slightly longer delay for video indexing
                refreshThumbnail()
            }
        }
    }

    fun pauseVideo() {
        if (_uiState.value.isRecording && !_uiState.value.isPaused) {
            _uiState.update { it.copy(isPaused = true) }
            captureEngine.pauseVideo()
            pauseRecordingTimer()
        }
    }

    fun resumeVideo() {
        if (_uiState.value.isRecording && _uiState.value.isPaused) {
            _uiState.update { it.copy(isPaused = false) }
            captureEngine.resumeVideo()
            startRecordingTimer()
        }
    }

    fun takePhotoDuringVideo() {
        captureEngine.captureVideoSnapshot(
            onSuccess = { _ ->
                // Maybe play a sound or show a flash
            },
            onError = { it.printStackTrace() },
        )
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                secondsRecorded++
                val minutes = secondsRecorded / 60
                val seconds = secondsRecorded % 60
                val duration = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
                _uiState.update { it.copy(recordingDuration = duration) }
            }
        }
    }

    private fun pauseRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        secondsRecorded = 0
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

    private fun setupGestureTrigger() {
        captureEngine.setOnGestureListener {
            viewModelScope.launch {
                // If a timer is already running or we're recording, don't trigger
                if (timerJob == null && !_uiState.value.isRecording && _uiState.value.captureType == CaptureType.PHOTO) {
                    startTimer(3)
                }
            }
        }
    }

    fun setZoom(ratio: Float) {
        val capabilities = _uiState.value.capabilities
        val minZoom = capabilities?.zoomRange?.lower ?: 0.5f
        val maxZoom = capabilities?.zoomRange?.upper ?: 10.0f
        val clampedRatio = ratio.coerceIn(minZoom, maxZoom)
        
        if (abs(clampedRatio - _uiState.value.zoomLevel) > 0.001f) {
            _uiState.update { it.copy(zoomLevel = clampedRatio) }
            captureEngine.setZoom(clampedRatio)
        }
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

            // Auto leveler fix: if pitch is too steep, it's hard to define a "horizon"
            // Instead of sticking, we should clear the tilt or use a wider threshold
            if (abs(pitch) < 60) {
                currentTilt += alpha * (roll - currentTilt)
                _uiState.update { it.copy(horizonTilt = currentTilt) }
            } else {
                // Fade out leveler if device is too vertical/horizontal
                _uiState.update { it.copy(horizonTilt = 0f) }
            }
        } else if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            
            val roll = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat()
            val pitch = Math.toDegrees(atan2(az.toDouble(), sqrt((ax * ax + ay * ay).toDouble()))).toFloat()
            
            updateOrientationFromRoll(roll)

            if (abs(pitch) < 60) {
                currentTilt += alpha * (roll - currentTilt)
                _uiState.update { it.copy(horizonTilt = currentTilt) }
            } else {
                _uiState.update { it.copy(horizonTilt = 0f) }
            }
        }
    }

    private fun updateOrientationFromRoll(roll: Float) {
        val newOrientation = when {
            roll > -45 && roll <= 45 -> 0
            roll > 45 && roll <= 135 -> 270
            roll > -135 && roll <= -45 -> 90
            else -> 180
        }
        
        // Disable automatic leveler in landscape for a cleaner UI matching reference
        val shouldAutoShowLeveler = false 
        
        if (newOrientation != _uiState.value.orientation || shouldAutoShowLeveler != _uiState.value.autoShowLeveler) {
            _uiState.update { it.copy(
                orientation = newOrientation,
                autoShowLeveler = shouldAutoShowLeveler
            ) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        stopLeveler()
    }
}
