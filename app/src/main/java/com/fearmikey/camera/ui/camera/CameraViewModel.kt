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
import com.fearmikey.camera.data.engine.processing.PanoramaTooShortException
import com.fearmikey.camera.data.repository.MediaRepository
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    private val _focusState = MutableStateFlow<FocusState?>(null)
    val focusState: StateFlow<FocusState?> = _focusState.asStateFlow()

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

    /** A finished sweep is still being stitched; a new one waits so memory stays bounded. */
    private var panoramaStitching = false
    private var panoramaMessageJob: Job? = null

    private var smoothedHandBox: androidx.compose.ui.geometry.Rect? = null
    private var smoothedFaceBox: androidx.compose.ui.geometry.Rect? = null
    private val boxSmoothing = 0.5f 

    // QuickTake (long-press shutter in photo mode records a clip).
    private var quickTakePending = false
    private var quickTakeActive = false
    private var quickTakeReturnMode: CameraMode? = null
    private var messageJob: Job? = null

    init {
        refreshThumbnail()
        observePreferences()
        setupGestureTrigger()
        setupPanoramaListener()
        captureEngine.setOnCameraReadyListener { onCameraReady() }
        captureEngine.setOnLowLightListener { dark -> _uiState.update { it.copy(lowLightSuggested = dark) } }
        captureEngine.setOnQrCodeListener { text -> _uiState.update { it.copy(qrCode = text) } }
    }

    fun dismissQrCode() = _uiState.update { it.copy(qrCode = null) }

    fun acceptNightSuggestion() {
        _uiState.update { it.copy(lowLightSuggested = false) }
        setMode(CameraMode.NIGHT_SIGHT)
    }

    fun dismissNightSuggestion() = _uiState.update { it.copy(lowLightSuggested = false) }

    /** Hardware volume keys. Returns true if the key was consumed. */
    fun onVolumeKey(up: Boolean): Boolean = when (_uiState.value.userPreferences.volumeKeyAction) {
        VolumeKeyAction.SHUTTER -> { toggleCapture(); true }
        VolumeKeyAction.ZOOM -> {
            val z = _uiState.value.zoomLevel
            setZoom(if (up) z * 1.25f else z / 1.25f)
            true
        }
        VolumeKeyAction.VOLUME -> false
    }

    fun updateHdrPhoto(enabled: Boolean) = viewModelScope.launch { userPreferencesRepository.updateHdrPhoto(enabled) }
    fun updateVideoFrameRate(rate: VideoFrameRate) = viewModelScope.launch { userPreferencesRepository.updateVideoFrameRate(rate) }
    fun updateRecordAudio(enabled: Boolean) = viewModelScope.launch { userPreferencesRepository.updateRecordAudio(enabled) }

    private fun onCameraReady() {
        val capabilities = captureEngine.getCapabilities()
        _uiState.update {
            it.copy(
                capabilities = capabilities,
                zoomPresets = capabilities?.zoomPresets ?: listOf(1.0f)
            )
        }
        if (quickTakePending) {
            quickTakePending = false
            quickTakeActive = true
            startVideo()
        }
        applyTorch()
    }

    /** In video, "Flash On" means a continuous torch. */
    private fun applyTorch() {
        val state = _uiState.value
        val torch = state.captureType == CaptureType.VIDEO &&
            state.userPreferences.lightMode == LightMode.FLASH && !state.isFrontCamera
        captureEngine.setTorchEnabled(torch)
    }

    /** Quick flash toggle in the top bar: Off -> Auto -> On (Auto is skipped in video). */
    fun cycleFlash() {
        val state = _uiState.value
        val next = when (state.userPreferences.lightMode) {
            LightMode.NONE -> if (state.captureType == CaptureType.VIDEO) LightMode.FLASH else LightMode.AUTO_NIGHT_SIGHT
            LightMode.AUTO_NIGHT_SIGHT -> LightMode.FLASH
            LightMode.FLASH -> LightMode.NONE
        }
        updateLightMode(next)
    }

    /** Brightness slider shown after tap-to-focus. */
    fun setExposureIndex(index: Int) {
        val range = _uiState.value.capabilities?.exposureRange ?: return
        val clamped = index.coerceIn(range.lower, range.upper)
        if (clamped == _uiState.value.exposureIndex) return
        _uiState.update { it.copy(exposureIndex = clamped) }
        captureEngine.setExposureCompensation(clamped)
        // Keep the focus ring (and slider) visible while the user adjusts.
        scheduleFocusDismiss()
    }

    fun updateVideoResolution(resolution: VideoResolution) {
        viewModelScope.launch { userPreferencesRepository.updateVideoResolution(resolution) }
    }

    fun toggleVideoStabilization() {
        viewModelScope.launch {
            userPreferencesRepository.updateVideoStabilization(!_uiState.value.userPreferences.videoStabilization)
        }
    }

    /** Returns all manual controls to automatic. */
    fun resetProConfig() = updateProConfig(ProConfig())

    /** Shows a short status message in the top pill. */
    private fun showMessage(message: String, durationMs: Long = 2500) {
        messageJob?.cancel()
        _uiState.update { it.copy(backgroundTaskMessage = message) }
        messageJob = viewModelScope.launch {
            delay(durationMs)
            if (_uiState.value.backgroundTaskMessage == message) {
                _uiState.update { it.copy(backgroundTaskMessage = null) }
            }
        }
    }

    /** Device rotation from an OrientationEventListener (Surface.ROTATION_*). */
    fun setDeviceRotation(rotation: Int) {
        captureEngine.setTargetRotation(rotation)
    }

    fun onResume() {
        startLeveler()
    }

    fun onPause() {
        sensorManager?.unregisterListener(this)
        if (_uiState.value.isRecording) stopVideo()
        cancelTimer()
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
                captureEngine.setFlashMode(prefs.effectiveFlashMode)
                applyTorch()
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
            val modes = GridMode.entries
            val nextMode = modes[(modes.indexOf(currentMode) + 1) % modes.size]
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

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner, 
        surfaceProvider: Preview.SurfaceProvider,
        meteringPointFactoryProvider: () -> androidx.camera.core.MeteringPointFactory? = { null }
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider
        
        val selector = if (_uiState.value.isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        currentMeteringFactoryProvider = meteringPointFactoryProvider
        // Capabilities are refreshed in onCameraReady() once binding completes.
        captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector, meteringPointFactoryProvider)
    }

    private var currentMeteringFactoryProvider: () -> androidx.camera.core.MeteringPointFactory? = { null }

    fun toggleCamera() {
        if (_uiState.value.isRecording || _uiState.value.isProcessing) return
        val nextIsFront = !_uiState.value.isFrontCamera
        _uiState.update { it.copy(isFrontCamera = nextIsFront, zoomLevel = 1.0f, exposureIndex = 0) }
        captureEngine.setExposureCompensation(0)
        
        val lifecycleOwner = currentLifecycleOwner
        val surfaceProvider = currentSurfaceProvider
        
        if ((lifecycleOwner != null) && (surfaceProvider != null)) {
            val selector = if (nextIsFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector, currentMeteringFactoryProvider)
        }
    }

    fun setCaptureType(type: CaptureType) {
        if (_uiState.value.isRecording) return
        _uiState.update { 
            val nextMode = if (type == CaptureType.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO
            it.copy(captureType = type, currentMode = nextMode) 
        }
        captureEngine.setMode(if (type == CaptureType.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO)
        applyTorch()
    }

    fun setMode(mode: CameraMode) {
        if (_uiState.value.isRecording || _uiState.value.isPanoramaCapturing) return
        _uiState.update { it.copy(currentMode = mode, lowLightSuggested = false, qrCode = null, panoramaProgress = null) }
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
                android.util.Log.e("CameraViewModel", "Capture failed", error)
                _uiState.update { it.copy(isProcessing = false) }
                showMessage("Capture failed")
            }
        )
    }

    private fun handlePanoramaCapture() {
        if (_uiState.value.isPanoramaCapturing) {
            stopPanoramaCapture()
            return
        }
        if (panoramaStitching) {
            showMessage("Still stitching the last panorama")
            return
        }
        _uiState.update { it.copy(isPanoramaCapturing = true, panoramaProgress = null) }
        captureEngine.playStartRecordingSound()
        captureEngine.startPanorama(
            onSuccess = {},
            onError = { error ->
                android.util.Log.e("CameraViewModel", "Panorama start failed", error)
                _uiState.update { it.copy(isPanoramaCapturing = false, panoramaProgress = null) }
                showMessage("Panorama unavailable")
            }
        )
    }

    private fun stopPanoramaCapture() {
        if (!_uiState.value.isPanoramaCapturing) return
        panoramaStitching = true
        captureEngine.playStopRecordingSound()
        _uiState.update {
            it.copy(isPanoramaCapturing = false, panoramaProgress = null, backgroundTaskMessage = "Stitching panorama...")
        }
        captureEngine.stopPanorama(
            onSuccess = { uriString ->
                panoramaStitching = false
                _uiState.update { it.copy(lastCapturedUri = uriString.toUri()) }
                setPanoramaMessage("Panorama saved", 2500)
                viewModelScope.launch {
                    delay(500.milliseconds)
                    refreshThumbnail()
                }
            },
            onError = { error ->
                panoramaStitching = false
                android.util.Log.e("CameraViewModel", "Panorama failed", error)
                val text = if (error is PanoramaTooShortException) error.message!! else "Stitching failed"
                setPanoramaMessage(text, 3000)
            }
        )
    }

    private fun setPanoramaMessage(text: String, durationMs: Long) {
        panoramaMessageJob?.cancel()
        _uiState.update { it.copy(backgroundTaskMessage = text) }
        panoramaMessageJob = viewModelScope.launch {
            delay(durationMs)
            if (_uiState.value.backgroundTaskMessage == text) {
                _uiState.update { it.copy(backgroundTaskMessage = null) }
            }
        }
    }

    fun toggleCapture() {
        val state = _uiState.value
        if (CameraModes.isVideo(state.currentMode)) {
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
                android.util.Log.e("CameraViewModel", "Recording failed", error)
                stopRecordingTimer()
                _uiState.update { it.copy(isRecording = false, isPaused = false) }
                endQuickTake()
                showMessage(error.message ?: "Recording failed")
            }
        )
    }

    fun stopVideo() {
        stopRecordingTimer()
        captureEngine.playStopRecordingSound()
        // Update UI immediately; the URI arrives once the file is finalized.
        _uiState.update { it.copy(isRecording = false, isPaused = false) }
        captureEngine.stopSlowMoVideo { uriString ->
            _uiState.update { it.copy(lastCapturedUri = uriString.toUri()) }
            endQuickTake()
        }
    }

    private fun endQuickTake() {
        if (!quickTakeActive && !quickTakePending) return
        quickTakeActive = false
        quickTakePending = false
        quickTakeReturnMode?.let { captureEngine.setMode(it) }
        quickTakeReturnMode = null
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
        captureEngine.playShutterSound()
        captureEngine.captureVideoSnapshot(
            onSuccess = { uriString -> _uiState.update { it.copy(lastCapturedUri = uriString.toUri()) } },
            onError = { showMessage("Snapshot not available") },
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
        val state = _uiState.value
        if (state.isRecording || state.isProcessing || timerJob != null) return
        when (state.currentMode) {
            CameraMode.VIDEO -> startVideo()
            CameraMode.PHOTO, CameraMode.PORTRAIT -> {
                // Temporarily switch the engine to video; recording starts in onCameraReady().
                quickTakeReturnMode = state.currentMode
                quickTakePending = true
                captureEngine.setMode(CameraMode.VIDEO)
            }
            else -> Unit
        }
    }

    fun quickTakeStop() {
        when {
            quickTakePending -> endQuickTake() // released before the camera was ready
            _uiState.value.isRecording -> stopVideo()
        }
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

    /** Sweep progress comes from image tracking in the native engine (no sensor dots). */
    private fun setupPanoramaListener() {
        captureEngine.setOnPanoramaProgressListener { progress ->
            val state = _uiState.value
            if (state.currentMode != CameraMode.PANORAMA) return@setOnPanoramaProgressListener
            // Idle thumbnails only before a sweep, sweep progress only during one.
            if (progress.isIdlePreview == state.isPanoramaCapturing) return@setOnPanoramaProgressListener
            _uiState.update { it.copy(panoramaProgress = progress) }
            if (!progress.isIdlePreview && progress.hint == PanoramaHint.COMPLETE) stopPanoramaCapture()
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
            val uri = withContext(Dispatchers.IO) {
                runCatching { mediaRepository.getLatestMediaUri() }.getOrNull()
            }
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

    private fun scheduleFocusDismiss() {
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            delay(4000)
            _focusState.value = null
        }
    }

    fun focusAt(x: Float, y: Float) {
        val offset = androidx.compose.ui.geometry.Offset(x, y)
        _focusState.value = FocusState(offset, isLocked = false)
        // A new tap starts from neutral brightness, as on most camera apps.
        if (_uiState.value.exposureIndex != 0) {
            _uiState.update { it.copy(exposureIndex = 0) }
            captureEngine.setExposureCompensation(0)
        }
        
        captureEngine.focusAt(x, y) { success ->
            if (success) {
                _focusState.update { 
                    it?.copy(isLocked = true)
                }
            }
        }
        scheduleFocusDismiss()
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
        // Listener is registered in onResume() and removed in onPause().
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
        sensorManager?.unregisterListener(this)
    }
}
