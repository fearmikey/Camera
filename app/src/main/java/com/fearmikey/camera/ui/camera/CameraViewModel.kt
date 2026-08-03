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

data class GestureOverlayState(
    val handBox: androidx.compose.ui.geometry.Rect? = null,
    val faceBox: androidx.compose.ui.geometry.Rect? = null,
    val isTimerPending: Boolean = false,
)

class CameraViewModel(
    private val captureEngine: CaptureEngine,
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel(), SensorEventListener {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _tiltState = MutableStateFlow(0f)
    val tiltState: StateFlow<Float> = _tiltState.asStateFlow()

    private val _gestureState = MutableStateFlow(GestureOverlayState())
    val gestureState: StateFlow<GestureOverlayState> = _gestureState.asStateFlow()

    private val _orientationState = MutableStateFlow(0)
    val orientationState: StateFlow<Int> = _orientationState.asStateFlow()

    private val _focusState = MutableStateFlow<androidx.compose.ui.geometry.Offset?>(null)
    val focusState: StateFlow<androidx.compose.ui.geometry.Offset?> = _focusState.asStateFlow()

    private val _autoShowLeveler = MutableStateFlow(false)
    val autoShowLeveler: StateFlow<Boolean> = _autoShowLeveler.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    
    // Low-pass filter variables
    private var currentTilt = 0f
    private val alpha = 0.3f // Increased for more responsiveness
    
    private var currentYaw = 0f
    private var currentPitch = 0f
    
    private var focusJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var timerJob: Job? = null
    private var gestureTriggerJob: Job? = null
    private var boxClearJob: Job? = null
    private var secondsRecorded = 0L

    private var panoramaInitialYaw = 0f
    private var panoramaInitialPitch = 0f
    private val panoramaTargets = listOf(-45f, -30f, -15f, 0f, 15f, 30f, 45f)
    private var lastAutoCaptureTime = 0L

    private var smoothedHandBox: androidx.compose.ui.geometry.Rect? = null
    private var smoothedFaceBox: androidx.compose.ui.geometry.Rect? = null
    private val boxSmoothing = 0.5f 

    init {
        refreshThumbnail()
        observePreferences()
        setupGestureTrigger()
        setupPanoramaListener()
        captureEngine.setPanoramaYawProvider { currentYaw }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { prefs ->
                val prevShowLeveler = _uiState.value.userPreferences.showLeveler
                _uiState.update { it.copy(
                    userPreferences = prefs,
                    flashMode = prefs.flashMode,
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
        
        if ((lifecycleOwner != null) && (surfaceProvider != null)) {
            val selector = if (nextIsFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector)
            
            // Update capabilities for the new camera
            val capabilities = captureEngine.getCapabilities()
            _uiState.update { it.copy(
                capabilities = capabilities,
                zoomPresets = capabilities?.zoomPresets ?: listOf(1.0f)
            ) }
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
        if (_uiState.value.currentMode == CameraMode.PANORAMA) {
            handlePanoramaCapture()
            return
        }

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

    private fun handlePanoramaCapture() {
        if (!_uiState.value.isPanoramaCapturing) {
            _uiState.update { it.copy(
                isPanoramaCapturing = true, 
                panoramaMessage = "Don't move!",
                panoramaYaw = 0f,
                panoramaPitch = 0f,
                panoramaTargets = panoramaTargets,
                panoramaCapturedIndices = emptySet()
            ) }
            
            // Set current sensor angles as 0,0 reference
            panoramaInitialYaw = currentYaw
            panoramaInitialPitch = currentPitch
            
            // Allow the first capture to trigger immediately
            lastAutoCaptureTime = 0L

            captureEngine.startPanorama(
                onSuccess = {
                    // Manually trigger the first capture for the starting position (0,0)
                    // This provides the immediate "shutter click" feedback the user expects.
                    val centerIndex = panoramaTargets.indexOf(0f)
                    if (centerIndex >= 0) {
                        autoCapturePanorama(centerIndex)
                    }
                },
                onError = { error ->
                    error.printStackTrace()
                    _uiState.update { it.copy(isPanoramaCapturing = false, panoramaMessage = null) }
                }
            )
        } else {
            _uiState.update { it.copy(isPanoramaCapturing = false, panoramaMessage = null, backgroundTaskMessage = "Stitching Panorama...") }
            captureEngine.stopPanorama(
                onSuccess = { uriString ->
                    val uri = uriString.toUri()
                    _uiState.update { it.copy(lastCapturedUri = uri, backgroundTaskMessage = "Panorama Saved") }
                    
                    // Notify user with thumbnail and clear message
                    viewModelScope.launch {
                        delay(2500)
                        if (_uiState.value.backgroundTaskMessage == "Panorama Saved") {
                            _uiState.update { it.copy(backgroundTaskMessage = null) }
                        }
                    }
                    viewModelScope.launch {
                        delay(500.milliseconds)
                        refreshThumbnail()
                    }
                },
                onError = { error ->
                    error.printStackTrace()
                    _uiState.update { it.copy(backgroundTaskMessage = "Stitching Failed") }
                    viewModelScope.launch {
                        delay(3000)
                        if (_uiState.value.backgroundTaskMessage == "Stitching Failed") {
                            _uiState.update { it.copy(backgroundTaskMessage = null) }
                        }
                    }
                }
            )
        }
    }

    fun toggleCapture() {
        val state = _uiState.value
        if ((state.currentMode == CameraMode.VIDEO) || (state.currentMode == CameraMode.SLOW_MO)) {
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
        captureEngine.setOnGestureListener { result ->
            viewModelScope.launch {
                val currentHandBox = result.handBoundingBox
                val currentFaceBox = result.faceBoundingBox
                
                // 1. Smooth the face bounding box
                if (currentFaceBox != null) {
                    smoothedFaceBox = if (smoothedFaceBox == null) {
                        currentFaceBox
                    } else {
                        androidx.compose.ui.geometry.Rect(
                            left = smoothedFaceBox!!.left + boxSmoothing * (currentFaceBox.left - smoothedFaceBox!!.left),
                            top = smoothedFaceBox!!.top + boxSmoothing * (currentFaceBox.top - smoothedFaceBox!!.top),
                            right = smoothedFaceBox!!.right + boxSmoothing * (currentFaceBox.right - smoothedFaceBox!!.right),
                            bottom = smoothedFaceBox!!.bottom + boxSmoothing * (currentFaceBox.bottom - smoothedFaceBox!!.bottom)
                        )
                    }
                } else {
                    smoothedFaceBox = null
                }
                _gestureState.update { it.copy(faceBox = smoothedFaceBox) }
                
                if (currentHandBox != null) {
                    // 2. Smooth the hand bounding box
                    smoothedHandBox = if (smoothedHandBox == null) {
                        currentHandBox
                    } else {
                        androidx.compose.ui.geometry.Rect(
                            left = smoothedHandBox!!.left + boxSmoothing * (currentHandBox.left - smoothedHandBox!!.left),
                            top = smoothedHandBox!!.top + boxSmoothing * (currentHandBox.top - smoothedHandBox!!.top),
                            right = smoothedHandBox!!.right + boxSmoothing * (currentHandBox.right - smoothedHandBox!!.right),
                            bottom = smoothedHandBox!!.bottom + boxSmoothing * (currentHandBox.bottom - smoothedHandBox!!.bottom)
                        )
                    }
                    _gestureState.update { it.copy(handBox = smoothedHandBox) }
                    
                    // 3. Manage the "ghosting" timeout
                    boxClearJob?.cancel()
                    boxClearJob = launch {
                        delay(600) // Bridge detection gaps
                        smoothedHandBox = null
                        _gestureState.update { it.copy(
                            handBox = null,
                            isTimerPending = false
                        ) }
                        gestureTriggerJob?.cancel()
                        gestureTriggerJob = null
                    }

                    // 4. Trigger timer with a 1s delay
                    if (result.isTriggered && timerJob == null && gestureTriggerJob == null &&
                        !_uiState.value.isRecording && _uiState.value.captureType == CaptureType.PHOTO) {
                        
                        gestureTriggerJob = launch {
                            _gestureState.update { it.copy(isTimerPending = true) }
                            delay(1000) // 1s lock-on delay
                            _gestureState.update { it.copy(isTimerPending = false) }
                            startTimer(3)
                            gestureTriggerJob = null
                        }
                    }
                }
            }
        }
    }

    private fun setupPanoramaListener() {
        captureEngine.setOnPanoramaListener { message ->
            _uiState.update { it.copy(panoramaMessage = message) }
            // Clear message after a short delay
            viewModelScope.launch {
                delay(2000)
                if (_uiState.value.panoramaMessage == message) {
                    _uiState.update { it.copy(panoramaMessage = null) }
                }
            }
        }
    }

    private fun updatePanoramaState(yaw: Float, pitch: Float) {
        var deltaYaw = yaw - panoramaInitialYaw
        while (deltaYaw > 180) deltaYaw -= 360f
        while (deltaYaw < -180) deltaYaw += 360f
        
        val deltaPitch = pitch - panoramaInitialPitch

        _uiState.update { it.copy(panoramaYaw = deltaYaw, panoramaPitch = deltaPitch) }

        // Check for auto-capture alignment
        val state = _uiState.value
        val targets = state.panoramaTargets
        val captured = state.panoramaCapturedIndices
        
        for (i in targets.indices) {
            if (i !in captured) {
                val targetYaw = targets[i]
                if (abs(deltaYaw - targetYaw) < 1.0f && abs(deltaPitch) < 1.5f) {
                    // Align!
                    if (System.currentTimeMillis() - lastAutoCaptureTime > 1500) {
                        autoCapturePanorama(i)
                    }
                }
            }
        }
    }

    private fun autoCapturePanorama(index: Int) {
        lastAutoCaptureTime = System.currentTimeMillis()
        _uiState.update { it.copy(
            panoramaCapturedIndices = it.panoramaCapturedIndices + index,
            panoramaMessage = "Don't move!"
        ) }
        
        captureEngine.playShutterSound()
        // Here we could also signal the engine to prioritize this frame
        
        viewModelScope.launch {
            delay(800.milliseconds)
            if (_uiState.value.isPanoramaCapturing) {
                val captured = _uiState.value.panoramaCapturedIndices.size
                val total = _uiState.value.panoramaTargets.size
                if (captured < total) {
                    _uiState.update { it.copy(panoramaMessage = "Move to the next dot") }
                } else {
                    _uiState.update { it.copy(panoramaMessage = "Perfect! Press stop to finish") }
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
        _focusState.value = androidx.compose.ui.geometry.Offset(x, y)
        
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            delay(2000)
            _focusState.value = null
        }
    }

    fun initSensors(sensorManager: SensorManager) {
        this.sensorManager = sensorManager
        // Try Game Rotation Vector (No Magnetometer = No Drift/Twitching near metal)
        this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        
        if (this.rotationSensor == null) {
            // Fallback to absolute Rotation Vector
            this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }
        
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
        val sensorType = event?.sensor?.type ?: return
        
        if (sensorType == Sensor.TYPE_ROTATION_VECTOR || sensorType == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            // Pro-Level Fix: Remap the coordinate system for Portrait orientation
            // This ensures that 'Azimuth' (Yaw) is calculated correctly even when the 
            // phone is tilted (Pitch/Roll), preventing the dots from "drifting" non-linearly.
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix, 
                SensorManager.AXIS_X, 
                SensorManager.AXIS_Z, 
                remappedMatrix
            )
            
            val orientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, orientation)
            
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

            // Apply Low-Pass Filter (Exponential Smoothing)
            val smoothFactor = 0.15f // Slightly smoother for higher precision
            
            // 1. Smooth Pitch
            currentPitch += smoothFactor * (pitch - currentPitch)
            
            // 2. Smooth Yaw (with wrap-around protection)
            var yawDiff = azimuth - currentYaw
            while (yawDiff > 180) yawDiff -= 360f
            while (yawDiff < -180) yawDiff += 360f
            
            currentYaw += smoothFactor * yawDiff
            
            // Normalize currentYaw to -180..180
            while (currentYaw > 180) currentYaw -= 360f
            while (currentYaw < -180) currentYaw += 360f
            
            updateOrientationFromRoll(roll)

            if (_uiState.value.isPanoramaCapturing) {
                updatePanoramaState(currentYaw, currentPitch)
            }

            // Auto leveler logic
            if (abs(currentPitch) < 60) {
                currentTilt += alpha * (roll - currentTilt)
                _tiltState.value = currentTilt
            } else {
                _tiltState.value = 0f
            }
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            
            val roll = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat()
            val pitch = Math.toDegrees(atan2(az.toDouble(), sqrt((ax * ax + ay * ay).toDouble()))).toFloat()
            
            updateOrientationFromRoll(roll)

            if (abs(pitch) < 60) {
                currentTilt += alpha * (roll - currentTilt)
                _tiltState.value = currentTilt
            } else {
                _tiltState.value = 0f
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
        
        val shouldAutoShowLeveler = false 
        
        if (newOrientation != _orientationState.value || shouldAutoShowLeveler != _autoShowLeveler.value) {
            _orientationState.value = newOrientation
            _autoShowLeveler.value = shouldAutoShowLeveler
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        stopLeveler()
    }
}
