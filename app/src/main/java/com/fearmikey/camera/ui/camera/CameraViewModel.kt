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
import android.content.ContentResolver
import android.net.Uri
import com.fearmikey.camera.data.engine.CaptureEngine
import com.fearmikey.camera.data.engine.MeteringPointProvider
import com.fearmikey.camera.data.engine.analysis.CodeDecoder
import com.fearmikey.camera.data.engine.analysis.CodeScanFrame
import com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.asin
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
    /** Lock-screen camera: the thumbnail only shows what was captured in this session. */
    secureSession: Boolean = false,
) : ViewModel(), SensorEventListener {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /**
     * Live zoom, separate from [uiState] because it changes every frame while pinching or
     * gliding (collecting it doesn't recompose the whole screen). Read `.value` for the latest.
     */
    private val _zoomState = MutableStateFlow(ZoomUiState())
    val zoomState: StateFlow<ZoomUiState> = _zoomState.asStateFlow()

    /**
     * Panorama corner preview / sweep progress (one update per analysis frame in Panorama),
     * separate from [uiState] for the same reason. Null outside Panorama and between sweeps.
     */
    private val _panoramaProgress = MutableStateFlow<PanoramaProgress?>(null)
    val panoramaProgress: StateFlow<PanoramaProgress?> = _panoramaProgress.asStateFlow()

    /** Device attitude for the horizon (landscape) and top-down levelers. */
    private val _levelState = MutableStateFlow(LevelState())
    val levelState: StateFlow<LevelState> = _levelState.asStateFlow()

    private val _gestureState = MutableStateFlow(GestureOverlayState())
    val gestureState: StateFlow<GestureOverlayState> = _gestureState.asStateFlow()

    private val _orientationState = MutableStateFlow(0)
    val orientationState: StateFlow<Int> = _orientationState.asStateFlow()

    private val _focusState = MutableStateFlow<FocusState?>(null)
    val focusState: StateFlow<FocusState?> = _focusState.asStateFlow()

    /** Latest live-scan frame while a code is in view (outline overlay); null otherwise. */
    private val _codeScanFrame = MutableStateFlow<CodeScanFrame?>(null)
    val codeScanFrame: StateFlow<CodeScanFrame?> = _codeScanFrame.asStateFlow()
    private val dismissedCodes = mutableSetOf<String>()

    /** Live face boxes in Portrait mode (viewfinder overlay); EMPTY outside Portrait. */
    private val _portraitFaces = MutableStateFlow(PortraitFaceFrame.EMPTY)
    val portraitFaces: StateFlow<PortraitFaceFrame> = _portraitFaces.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    
    // Sensor state (main thread). "Up" is the gravity direction in device coordinates,
    // low-passed as a vector so angles never wrap around.
    private val rotationMatrix = FloatArray(9)
    private val smoothUp = FloatArray(3)
    private var hasUp = false
    /** Camera viewing direction in world coordinates (accelerometer fallback: "up"). */
    private val lookDir = FloatArray(3)
    private var hasLookDir = false
    /** Viewing direction when focus was tapped; moving away from it drops the lock. */
    private var focusAnchor: FloatArray? = null
    /** Sensor timestamp (ns) since the camera has pointed down, 0 when it isn't. */
    private var faceDownSinceNs = 0L
    
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
        // The newest gallery item may be a private photo: never show it over the lock screen.
        if (!secureSession) refreshThumbnail()
        observePreferences()
        setupGestureTrigger()
        setupPanoramaListener()
        captureEngine.setOnCameraReadyListener { onCameraReady() }
        captureEngine.setOnLowLightListener { dark -> onLowLight(dark) }
        captureEngine.setOnCaptureProgressListener { p ->
            // Ignore stragglers from a capture that already finished or timed out.
            _uiState.update { if (it.isProcessing && p > it.captureProgress) it.copy(captureProgress = p) else it }
        }
        captureEngine.setOnCodeScanListener { frame -> onCodeScanFrame(frame) }
        captureEngine.setOnPortraitFacesListener { frame -> _portraitFaces.value = frame }
    }

    private fun onCodeScanFrame(frame: CodeScanFrame) {
        _codeScanFrame.value = frame.takeIf { it.inView.isNotEmpty() }
        // A dismissed code stays hidden until it has left the view for a while.
        dismissedCodes.retainAll(frame.codes.mapTo(HashSet()) { it.text })
        val shown = frame.codes.filter { it.text !in dismissedCodes }
        val current = _uiState.value.scannedCodes
        // Only push when the set of codes changes; corner updates go through codeScanFrame.
        if (shown.map { it.text } != current.map { it.text }) {
            _uiState.update { it.copy(scannedCodes = shown) }
        }
    }

    /** Hides the current scan suggestion (until those codes leave the view). */
    fun dismissScannedCodes() {
        dismissedCodes += _uiState.value.scannedCodes.map { it.text }
        _uiState.update { it.copy(scannedCodes = emptyList()) }
    }

    /** Opens the result sheet for the codes currently suggested. */
    fun openScannedCodes() {
        val codes = _uiState.value.scannedCodes
        if (codes.isEmpty()) return
        dismissScannedCodes()
        _uiState.update { it.copy(codeSheet = codes) }
    }

    fun closeCodeSheet() = _uiState.update { it.copy(codeSheet = null) }

    /** Decodes QR codes/barcodes in a picked image (Photo Picker) and shows them in the result sheet. */
    fun scanCodesInImage(resolver: ContentResolver, uri: Uri) {
        if (_uiState.value.isScanningImage) return
        _uiState.update { it.copy(isScanningImage = true) }
        viewModelScope.launch {
            val codes = withContext(Dispatchers.Default) { CodeDecoder.decodeImage(resolver, uri) }
            _uiState.update { it.copy(isScanningImage = false, codeSheet = codes.ifEmpty { null }) }
            if (codes.isEmpty()) showMessage("No QR code or barcode found")
        }
    }

    /**
     * Scene brightness from the engine. With "Auto Night" the dark state switches the next
     * Photo shot to the Night pipeline (no chip); otherwise it offers Night mode if enabled.
     */
    private fun onLowLight(dark: Boolean) {
        _uiState.update {
            val prefs = it.userPreferences
            it.copy(
                isLowLight = dark,
                lowLightSuggested = dark && prefs.lowLightSuggestion && prefs.lightMode != LightMode.AUTO_NIGHT_SIGHT,
            )
        }
    }

    fun acceptNightSuggestion() {
        _uiState.update { it.copy(lowLightSuggested = false) }
        setMode(CameraMode.NIGHT_SIGHT)
    }

    fun dismissNightSuggestion() = _uiState.update { it.copy(lowLightSuggested = false) }

    /** Hardware volume keys. Returns true if the key was consumed. */
    fun onVolumeKey(up: Boolean): Boolean = when (_uiState.value.userPreferences.volumeKeyAction) {
        VolumeKeyAction.SHUTTER -> { toggleCapture(); true }
        VolumeKeyAction.ZOOM -> {
            // Step from where a running glide is heading so repeated presses accumulate.
            val z = _zoomState.value.let { it.target ?: it.level }
            animateZoomTo(if (up) z * 1.25f else z / 1.25f)
            true
        }
        VolumeKeyAction.VOLUME -> false
    }

    fun updateHdrPhoto(enabled: Boolean) = viewModelScope.launch { userPreferencesRepository.updateHdrPhoto(enabled) }
    fun updateVideoFrameRate(rate: VideoFrameRate) = viewModelScope.launch { userPreferencesRepository.updateVideoFrameRate(rate) }
    fun updateRecordAudio(enabled: Boolean) = viewModelScope.launch { userPreferencesRepository.updateRecordAudio(enabled) }
    fun updatePortraitAperture(fNumber: Float) = viewModelScope.launch { userPreferencesRepository.updatePortraitAperture(fNumber) }

    private fun onCameraReady() {
        val capabilities = captureEngine.getCapabilities()
        _uiState.update {
            val withCaps = it.copy(capabilities = capabilities)
            withCaps.copy(
                zoomPresets = zoomPolicy(withCaps).presets,
                zoomLabelScale = capabilities?.zoomLabelScale ?: 1f,
            )
        }
        if (pendingDefaultZoom && capabilities != null) {
            pendingDefaultZoom = false
            applyZoom(zoomPolicy().defaultRatio)
        }
        if (quickTakePending) {
            quickTakePending = false
            quickTakeActive = true
            startVideo()
        }
        applyTorch()
    }

    /** In video modes, "Flash On" means a continuous torch (rear camera only). */
    private fun applyTorch() {
        val caps = _uiState.value.modeCaps
        captureEngine.setTorchEnabled(caps.flashIsTorch && caps.effectiveLightMode == LightMode.FLASH)
    }

    /**
     * Keeps state consistent with what the current mode supports: the manual controls panel
     * closes where the capture path ignores it (Night, Long exposure, HDR, Panorama, Camera2
     * video), and the torch follows the mode.
     */
    private fun enforceModeCapabilities() {
        val state = _uiState.value
        if (state.isProMode && !state.modeCaps.supportsManualControls) setProModeEnabled(false)
        applyTorch()
    }

    /**
     * Quick flash toggle in the top bar, cycling through what this mode offers
     * (ModeCapabilities.lightModes): Photo Off -> Auto Night -> Auto flash -> On, Portrait
     * without Auto Night, video modes torch Off <-> On.
     */
    fun cycleFlash() {
        val next = _uiState.value.modeCaps.nextLightMode() ?: return
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
                _uiState.update { it.copy(userPreferences = prefs) }
                captureEngine.applySettings(prefs)
                captureEngine.setFlashMode(prefs.effectiveFlashMode)
                enforceModeCapabilities()
                captureEngine.setGestureTriggerEnabled(prefs.gestureTrigger)
                // No more detections arrive once the palm timer is off: drop the last boxes.
                if (!prefs.gestureTrigger) clearGestureOverlay()
                
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

    fun updateResolution(resolution: ResolutionMode) {
        viewModelScope.launch { userPreferencesRepository.updateResolution(resolution) }
    }

    fun updateSaveMode(saveMode: ImageSaveMode) {
        viewModelScope.launch { userPreferencesRepository.updateSaveMode(saveMode) }
    }

    fun updateGridMode(mode: GridMode) {
        viewModelScope.launch { userPreferencesRepository.updateGridMode(mode) }
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
        // Settings can rebind the camera, which would end a recording or a sweep.
        val state = _uiState.value
        if (show && (state.isRecording || state.isPanoramaCapturing)) return
        _uiState.update { it.copy(showSettingsSheet = show) }
    }

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner, 
        surfaceProvider: Preview.SurfaceProvider,
        meteringPointProvider: MeteringPointProvider = { _, _, _ -> null }
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentSurfaceProvider = surfaceProvider
        
        val selector = if (_uiState.value.isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        currentMeteringPointProvider = meteringPointProvider
        // Capabilities are refreshed in onCameraReady() once binding completes.
        captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector, meteringPointProvider)
    }

    private var currentMeteringPointProvider: MeteringPointProvider = { _, _, _ -> null }

    fun toggleCamera() {
        val state = _uiState.value
        if (state.isRecording || state.isProcessing || state.isPanoramaCapturing) return
        val nextIsFront = !_uiState.value.isFrontCamera
        zoomJob?.cancel()
        zoomJob = null
        _uiState.update { it.copy(isFrontCamera = nextIsFront) }
        _zoomState.value = ZoomUiState()
        // The other camera opens at 1x; onCameraReady moves it to the mode's starting zoom.
        pendingDefaultZoom = true
        resetPerModeState()
        
        val lifecycleOwner = currentLifecycleOwner
        val surfaceProvider = currentSurfaceProvider
        
        if ((lifecycleOwner != null) && (surfaceProvider != null)) {
            val selector = if (nextIsFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider, selector, currentMeteringPointProvider)
        }
    }

    fun setCaptureType(type: CaptureType) {
        val state = _uiState.value
        if (state.isRecording || state.isPanoramaCapturing) return
        val nextMode = if (type == CaptureType.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO
        if (state.captureType == type && state.currentMode == nextMode) return
        _uiState.update {
            it.copy(
                captureType = type, currentMode = nextMode,
                lowLightSuggested = false, scannedCodes = emptyList(),
            )
        }
        _panoramaProgress.value = null
        captureEngine.setMode(nextMode)
        onModeZoomChanged(state.currentMode)
        resetPerModeState()
        enforceModeCapabilities()
    }

    fun setMode(mode: CameraMode) {
        if (_uiState.value.isRecording || _uiState.value.isPanoramaCapturing) return
        val previous = _uiState.value.currentMode
        if (mode == previous) return
        _uiState.update { it.copy(currentMode = mode, lowLightSuggested = false, scannedCodes = emptyList()) }
        _panoramaProgress.value = null
        captureEngine.setMode(mode)
        onModeZoomChanged(previous)
        resetPerModeState()
        enforceModeCapabilities()
    }

    /**
     * Settings that belong to what you were just shooting don't follow you into another mode or
     * camera (they used to carry over invisibly): brightness back to 0, the tap-focus lock
     * released, the manual controls panel closed (its values are kept for when it is reopened),
     * and the palm-timer boxes cleared. Also explains HDR/RAW being off on the front camera.
     */
    private fun resetPerModeState() {
        if (_uiState.value.exposureIndex != 0) {
            _uiState.update { it.copy(exposureIndex = 0) }
            captureEngine.setExposureCompensation(0)
        }
        if (_focusState.value != null || focusAnchor != null) {
            focusJob?.cancel()
            focusAnchor = null
            _focusState.value = null
            captureEngine.cancelFocus()
        }
        if (_uiState.value.isProMode) setProModeEnabled(false)
        clearGestureOverlay()
        showFrontCameraHint()
    }

    /** Front camera in Photo: HDR and RAW + JPEG don't apply there (ModeCapabilities). Say so once per flip. */
    private var frontHintShown = false

    private fun showFrontCameraHint() {
        val state = _uiState.value
        if (!state.isFrontCamera) {
            frontHintShown = false
            return
        }
        if (frontHintShown || state.currentMode != CameraMode.PHOTO) return
        val prefs = state.userPreferences
        val raw = prefs.saveMode == ImageSaveMode.RAW_JPEG
        val unavailable = when {
            prefs.hdrPhoto && raw -> "HDR and RAW aren't"
            prefs.hdrPhoto -> "HDR isn't"
            raw -> "RAW isn't"
            else -> return
        }
        frontHintShown = true
        showMessage("$unavailable available on the front camera")
    }

    /** Drops the palm-timer hand/face boxes (they otherwise stay on screen once analysis stops). */
    private fun clearGestureOverlay() {
        boxClearJob?.cancel()
        boxClearJob = null
        gestureTriggerJob?.cancel()
        gestureTriggerJob = null
        smoothedHandBox = null
        smoothedFaceBox = null
        _gestureState.value = GestureOverlayState()
    }

    /**
     * Portrait has its own zoom range, presets and starting zoom (ZoomPolicy: 2x on the rear
     * camera, like Google Camera): glide there on entry, and back to the default on exit.
     * Panorama always starts at the default (1x): a sweep at 5x needs dozens of frames.
     */
    private fun onModeZoomChanged(previous: CameraMode) {
        val state = _uiState.value
        val zoom = _zoomState.value.level
        val policy = zoomPolicy(state)
        _uiState.update { it.copy(zoomPresets = policy.presets) }
        val portraitChanged = (previous == CameraMode.PORTRAIT) != (state.currentMode == CameraMode.PORTRAIT)
        val enteredPanorama = state.currentMode == CameraMode.PANORAMA && previous != CameraMode.PANORAMA
        when {
            state.capabilities == null -> pendingDefaultZoom = true
            portraitChanged || (enteredPanorama && zoom != policy.defaultRatio) ->
                animateZoomTo(policy.defaultRatio)
            policy.clamp(zoom) != zoom -> applyZoom(zoom)
        }
    }

    /** Zoom limits, presets and starting zoom for the current mode and camera. */
    private fun zoomPolicy(state: CameraUiState = _uiState.value): ZoomPolicy {
        val caps = state.capabilities
        return ZoomPolicy.forMode(
            mode = state.currentMode,
            isFront = state.isFrontCamera,
            minZoom = caps?.zoomRange?.lower ?: 0.5f,
            maxZoom = caps?.zoomRange?.upper ?: 10f,
            lensPresets = caps?.zoomPresets ?: listOf(1f),
            labelScale = caps?.zoomLabelScale ?: 1f,
        )
    }

    /** Apply the mode's starting zoom once the camera reports its capabilities. */
    private var pendingDefaultZoom = true

    fun takePicture() {
        if (timerJob != null) {
            cancelTimer()
            return
        }
        val state = _uiState.value
        if (state.isProcessing) return

        // No timer where it makes no sense (Panorama: the same tap also stops a sweep).
        val timerMode = state.userPreferences.timer
        if (timerMode != TimerMode.OFF && state.modeCaps.supportsTimer) {
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

        // One capture at a time (shutter, volume key, palm timer).
        if (_uiState.value.isProcessing) return
        // Snapshot the pipeline now: the scene state may change while the burst runs.
        _uiState.update { it.copy(isProcessing = true, capturePipeline = it.modeCaps.pipeline, captureProgress = 0f) }
        captureEngine.playShutterSound()
        captureEngine.capturePhoto(
            onSuccess = { uriString ->
                val uri = uriString.toUri()
                _uiState.update { it.copy(lastCapturedUri = uri, isProcessing = false, capturePipeline = null, captureProgress = 0f) }
                // Delay refresh to allow MediaStore to index the new file
                viewModelScope.launch {
                    delay(500.milliseconds)
                    refreshThumbnail()
                }
            },
            onError = { error ->
                android.util.Log.e("CameraViewModel", "Capture failed", error)
                _uiState.update { it.copy(isProcessing = false, capturePipeline = null, captureProgress = 0f) }
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
        _uiState.update { it.copy(isPanoramaCapturing = true) }
        _panoramaProgress.value = null
        captureEngine.playStartRecordingSound()
        captureEngine.startPanorama(
            onSuccess = {},
            onError = { error ->
                android.util.Log.e("CameraViewModel", "Panorama start failed", error)
                _uiState.update { it.copy(isPanoramaCapturing = false) }
                _panoramaProgress.value = null
                showMessage("Panorama unavailable")
            }
        )
    }

    private fun stopPanoramaCapture() {
        if (!_uiState.value.isPanoramaCapturing) return
        panoramaStitching = true
        captureEngine.playStopRecordingSound()
        _uiState.update {
            it.copy(isPanoramaCapturing = false, backgroundTaskMessage = "Stitching panorama...")
        }
        _panoramaProgress.value = null
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
        // Update UI immediately; the URI arrives once the file is finalized.
        _uiState.update { it.copy(isRecording = false, isPaused = false) }
        if (quickTakePending) {
            // Released (or paused) while the camera was still switching to video: nothing recorded.
            endQuickTake()
            return
        }
        captureEngine.stopSlowMoVideo(
            onSuccess = { uriString ->
                _uiState.update { it.copy(lastCapturedUri = uriString.toUri()) }
                endQuickTake()
            },
            onError = { error ->
                android.util.Log.e("CameraViewModel", "Stopping the recording failed", error)
                endQuickTake()
                showMessage(error.message ?: "Recording failed")
            },
        )
        // After stopping, so the chime can't end up in the clip.
        captureEngine.playStopRecordingSound()
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
        if (!_uiState.value.modeCaps.supportsVideoSnapshot) return
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
        if (state.isRecording || state.isProcessing || timerJob != null || !state.modeCaps.supportsQuickTake) return
        when (state.currentMode) {
            CameraMode.VIDEO -> startVideo()
            CameraMode.PHOTO, CameraMode.PORTRAIT -> {
                // Temporarily switch the engine to video; recording starts in onCameraReady().
                // Show the recording state right away so the hold feels instant while the
                // session reconfigures (the timer starts with the actual recording).
                quickTakeReturnMode = state.currentMode
                quickTakePending = true
                _uiState.update { it.copy(isRecording = true, isPaused = false, recordingDuration = "00:00") }
                captureEngine.setMode(CameraMode.VIDEO)
            }
            else -> Unit
        }
    }

    fun quickTakeStop() {
        // stopVideo() also handles a release before the camera was ready (quickTakePending).
        if (_uiState.value.isRecording) stopVideo()
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
                    
                    // 3. Hide the hand box after a detection gap. This only clears the overlay:
                    // a trigger that already fired must still start the timer once the hand is
                    // lowered (canceling it here meant the timer almost never started).
                    boxClearJob?.cancel()
                    boxClearJob = launch {
                        delay(600) // Bridge detection gaps
                        smoothedHandBox = null
                        _gestureState.update { it.copy(handBox = null) }
                    }

                    // 4. Trigger timer with a 1s delay
                    val state = _uiState.value
                    if (result.isTriggered && timerJob == null && gestureTriggerJob == null &&
                        !state.isRecording && !state.isProcessing && state.modeCaps.supportsTimer &&
                        state.captureType == CaptureType.PHOTO) {
                        
                        gestureTriggerJob = viewModelScope.launch {
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
            _panoramaProgress.value = progress
            if (!progress.isIdlePreview && progress.hint == PanoramaHint.COMPLETE) stopPanoramaCapture()
        }
    }

    /** Immediate zoom (pinch). Cancels a running preset glide. */
    fun setZoom(ratio: Float) {
        // Zooming mid-sweep would break panorama tracking.
        if (_uiState.value.isPanoramaCapturing) return
        if (zoomJob != null) {
            zoomJob?.cancel()
            zoomJob = null
            _zoomState.update { it.copy(target = null) }
        }
        applyZoom(ratio)
    }

    private var zoomJob: Job? = null
    /** Material standard easing (same curve as Compose's FastOutSlowInEasing). */
    private val zoomEasing = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /**
     * Glides the zoom to [ratio] (zoom presets, volume keys), one step per display frame.
     * Interpolated in log space so every doubling takes equal time (0.5x -> 1x feels like
     * 1x -> 2x); the duration grows with the distance (about 200 ms + 90 ms per doubling,
     * capped at 420 ms). On multi-lens phones the camera HAL switches lenses mid-glide, which
     * reads as one continuous move instead of a cut.
     */
    fun animateZoomTo(ratio: Float) {
        if (_uiState.value.isPanoramaCapturing) return
        val target = clampZoom(ratio)
        val start = _zoomState.value.level
        zoomJob?.cancel()
        zoomJob = null
        if (abs(target - start) < 0.001f || start <= 0f) {
            _zoomState.update { it.copy(target = null) }
            applyZoom(target)
            return
        }
        val doublings = abs(ln(target / start)) / ln(2f)
        val durationMs = (200f + 90f * doublings).coerceAtMost(420f)
        _zoomState.update { it.copy(target = target) }
        zoomJob = viewModelScope.launch {
            val choreographer = android.view.Choreographer.getInstance()
            val startNanos = awaitFrame(choreographer)
            var now = startNanos
            while (true) {
                val t = ((now - startNanos) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                applyZoom(start * (target / start).pow(zoomEasing.getInterpolation(t)))
                if (t >= 1f) break
                now = awaitFrame(choreographer)
            }
            applyZoom(target)
            _zoomState.update { it.copy(target = null) }
            zoomJob = null
        }
    }

    /** Suspends until the next display frame (vsync-aligned steps; viewModelScope is Main). */
    private suspend fun awaitFrame(choreographer: android.view.Choreographer): Long =
        suspendCancellableCoroutine { cont ->
            val callback = android.view.Choreographer.FrameCallback { nanos -> cont.resume(nanos) }
            choreographer.postFrameCallback(callback)
            cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        }

    private fun clampZoom(ratio: Float): Float = zoomPolicy().clamp(ratio)

    private fun applyZoom(ratio: Float) {
        val clampedRatio = clampZoom(ratio)
        if (abs(clampedRatio - _zoomState.value.level) > 0.001f) {
            _zoomState.update { it.copy(level = clampedRatio) }
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
        val state = _uiState.value
        if (!state.isProMode && !state.modeCaps.supportsManualControls) {
            // In Photo the only path that ignores them is HDR.
            showMessage(
                if (state.currentMode == CameraMode.PHOTO) "Turn off HDR to use manual controls"
                else "Manual controls aren't available in ${CameraModes.label(state.currentMode)}"
            )
            return
        }
        setProModeEnabled(!state.isProMode)
    }

    private fun setProModeEnabled(enabled: Boolean) {
        if (enabled) captureEngine.updateManualConfig(_uiState.value.proConfig)
        captureEngine.setProMode(enabled)
        _uiState.update { it.copy(isProMode = enabled) }
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

    /** Visible viewfinder width / height (photos are cropped to what it shows). */
    fun onViewfinderAspect(widthOverHeight: Float) {
        captureEngine.setViewfinderAspectRatio(widthOverHeight)
    }

    fun focusAt(x: Float, y: Float) {
        val offset = androidx.compose.ui.geometry.Offset(x, y)
        _focusState.value = FocusState(offset, isLocked = false)
        // The lock holds until the camera turns away from this direction (see onSensorChanged).
        focusAnchor = if (hasLookDir) lookDir.copyOf() else null
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
            // GAME rate (~50 Hz) keeps the bubble of the top-down leveler fluid.
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopLeveler() {
        // We only stop if we're not using orientation for icons either
        // But for this requirement, we want it always
        // sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val type = event.sensor.type
        val ux: Float
        val uy: Float
        val uz: Float
        val smoothing: Float
        when (type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Device->world matrix: its third row is world "up" in device axes, its third
                // column the screen normal (opposite the camera) in world axes.
                ux = rotationMatrix[6]; uy = rotationMatrix[7]; uz = rotationMatrix[8]
                lookDir[0] = rotationMatrix[2]; lookDir[1] = rotationMatrix[5]; lookDir[2] = rotationMatrix[8]
                hasLookDir = true
                smoothing = 0.25f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // At rest the accelerometer reads +g along "up".
                ux = event.values[0]; uy = event.values[1]; uz = event.values[2]
                smoothing = 0.12f
            }
            else -> return
        }
        if (hasUp) {
            smoothUp[0] += smoothing * (ux - smoothUp[0])
            smoothUp[1] += smoothing * (uy - smoothUp[1])
            smoothUp[2] += smoothing * (uz - smoothUp[2])
        } else {
            smoothUp[0] = ux; smoothUp[1] = uy; smoothUp[2] = uz
            hasUp = true
        }
        val norm = sqrt(smoothUp[0] * smoothUp[0] + smoothUp[1] * smoothUp[1] + smoothUp[2] * smoothUp[2])
        if (norm < 1e-3f) return
        val x = smoothUp[0] / norm
        val y = smoothUp[1] / norm
        val z = smoothUp[2] / norm
        if (type == Sensor.TYPE_ACCELEROMETER) {
            // Without a gyro, panning is invisible; tilting still releases a focus lock.
            lookDir[0] = x; lookDir[1] = y; lookDir[2] = z
            hasLookDir = true
        }
        releaseFocusIfMoved()

        // Roll in the screen plane: + when the phone is turned clockwise.
        val roll = Math.toDegrees(atan2(-x, y).toDouble()).toFloat()
        // Elevation of the screen normal: +90 = lying flat with the camera facing down.
        val screenUp = degreesAsin(z)
        // Roll is meaningless near flat, so the orientation keeps its last value there.
        val upright = abs(screenUp) < FLAT_ANGLE_DEG
        if (upright) updateOrientation(roll)

        val facingDown = updateFacingDown(tiltFromDown = 90f - screenUp, timestampNs = event.timestamp)
        val orientation = _orientationState.value
        _levelState.value = LevelState(
            roll = roll,
            isLandscape = upright && !facingDown && (orientation == 90 || orientation == 270),
            isFacingDown = facingDown,
            downTiltX = degreesAsin(x),
            downTiltY = degreesAsin(y),
        )
    }

    private fun degreesAsin(v: Float): Float = Math.toDegrees(asin(v.coerceIn(-1f, 1f).toDouble())).toFloat()

    /** Icon/UI orientation with hysteresis so it doesn't flicker around 45 degrees. */
    private fun updateOrientation(roll: Float) {
        val current = _orientationState.value
        val currentRoll = when (current) {
            270 -> 90f
            90 -> -90f
            180 -> 180f
            else -> 0f
        }
        var delta = roll - currentRoll
        if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
        if (abs(delta) < ORIENTATION_HYSTERESIS_DEG) return
        _orientationState.value = when {
            roll > -45 && roll <= 45 -> 0
            roll > 45 && roll <= 135 -> 270
            roll > -135 && roll <= -45 -> 90
            else -> 180
        }
    }

    /**
     * The top-down leveler appears once the camera has pointed within [FACE_DOWN_ENTER_DEG] of
     * straight down for [FACE_DOWN_DELAY_NS], and hides past [FACE_DOWN_EXIT_DEG].
     */
    private fun updateFacingDown(tiltFromDown: Float, timestampNs: Long): Boolean {
        if (_levelState.value.isFacingDown) {
            if (tiltFromDown < FACE_DOWN_EXIT_DEG) return true
            faceDownSinceNs = 0L
            return false
        }
        if (tiltFromDown >= FACE_DOWN_ENTER_DEG) {
            faceDownSinceNs = 0L
            return false
        }
        if (faceDownSinceNs == 0L) faceDownSinceNs = timestampNs
        return timestampNs - faceDownSinceNs >= FACE_DOWN_DELAY_NS
    }

    /** Moving the camera away from a tap-focused subject returns to continuous AF (as on Pixel). */
    private fun releaseFocusIfMoved() {
        val anchor = focusAnchor ?: return
        val dot = anchor[0] * lookDir[0] + anchor[1] * lookDir[1] + anchor[2] * lookDir[2]
        if (dot < FOCUS_RELEASE_COS) {
            focusAnchor = null
            focusJob?.cancel()
            _focusState.value = null
            captureEngine.cancelFocus()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        sensorManager?.unregisterListener(this)
    }

    private companion object {
        /** Beyond this elevation the phone counts as pointing up/down: no horizon or orientation change. */
        const val FLAT_ANGLE_DEG = 60f
        /** Roll must pass 45 + 10 degrees before the UI orientation flips. */
        const val ORIENTATION_HYSTERESIS_DEG = 55f
        const val FACE_DOWN_ENTER_DEG = 20f
        const val FACE_DOWN_EXIT_DEG = 30f
        const val FACE_DOWN_DELAY_NS = 1_000_000_000L
        /** cos(10 degrees): turning the camera further away drops a tap-focus lock. */
        const val FOCUS_RELEASE_COS = 0.985f
    }
}
