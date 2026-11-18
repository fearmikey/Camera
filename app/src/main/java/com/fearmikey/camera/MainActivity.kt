package com.fearmikey.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.view.OrientationEventListener
import android.view.Surface
import com.fearmikey.camera.data.repository.DeviceLocationProvider
import com.fearmikey.camera.data.repository.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.CameraModes
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.LightMode
import com.fearmikey.camera.domain.model.LensSelectionMode
import com.fearmikey.camera.SettingsActivity
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.ui.camera.CameraViewModel
import com.fearmikey.camera.ui.camera.CameraUiState
import com.fearmikey.camera.ui.camera.FocusState
import com.fearmikey.camera.ui.camera.ProControlOverlay
import com.fearmikey.camera.ui.camera.HudOverlay
import com.fearmikey.camera.ui.camera.CameraTopBar
import com.fearmikey.camera.ui.camera.ExposureSlider
import com.fearmikey.camera.ui.camera.PermissionRequiredScreen
import com.fearmikey.camera.ui.camera.CameraAccent
import com.fearmikey.camera.ui.camera.formatZoom
import com.fearmikey.camera.data.engine.CameraXProvider
import com.fearmikey.camera.data.engine.Camera2Provider
import com.fearmikey.camera.data.engine.HybridCaptureEngine
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.text.font.FontWeight
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
import com.fearmikey.camera.data.repository.MediaRepository
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.ui.settings.SettingsSheet
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: CameraViewModel
    private lateinit var locationProvider: DeviceLocationProvider
    private var isSecureCamera = false

    /** Feeds device rotation to the engine so captures are upright while the UI stays portrait. */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || !::viewModel.isInitialized) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                viewModel.setDeviceRotation(rotation)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            locationProvider.start()
        } else if (viewModel.uiState.value.userPreferences.locationTagging) {
            // Permission refused: turn the setting back off so the UI stays truthful.
            viewModel.toggleLocationTagging()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The camera UI is always dark, so keep light system-bar icons regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        
        if ((intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) ||
            (intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE) ||
            (intent.action == "android.media.action.IMAGE_CAPTURE_SECURE")) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            isSecureCamera = true
        }
        
        locationProvider = DeviceLocationProvider(this)
        val geotag: () -> android.location.Location? = {
            if (::viewModel.isInitialized && viewModel.uiState.value.userPreferences.locationTagging) {
                locationProvider.currentLocation()
            } else {
                null
            }
        }
        val processor = ImageProcessor(this)
        val mediaSaver = MediaSaver(this)
        val cameraXProvider = CameraXProvider(this, processor, mediaSaver, geotag)
        val camera2Provider = Camera2Provider(this, processor, mediaSaver, geotag)
        val hybridCaptureEngine = HybridCaptureEngine(cameraXProvider, camera2Provider)
        val mediaRepository = MediaRepository(this)
        val userPreferencesRepository = UserPreferencesRepository(this)
        viewModel = CameraViewModel(hybridCaptureEngine, mediaRepository, userPreferencesRepository)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        viewModel.initSensors(sensorManager)
        observeLocationSetting()

        setContent {
            com.fearmikey.camera.ui.theme.ViewfinderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    CameraScreen(viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::viewModel.isInitialized &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            // Ignore auto-repeat so holding the key doesn't fire a burst of shots.
            if ((event?.repeatCount ?: 0) > 0 &&
                viewModel.uiState.value.userPreferences.volumeKeyAction == com.fearmikey.camera.domain.model.VolumeKeyAction.SHUTTER
            ) return true
            if (viewModel.onVolumeKey(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        viewModel.onPause()
        orientationListener.disable()
        locationProvider.stop()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isSecureCamera) {
            finish()
        }
    }

    /** Starts/stops location updates while resumed, requesting permission when the setting is enabled. */
    private fun observeLocationSetting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState
                    .map { it.userPreferences.locationTagging }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        when {
                            !enabled -> locationProvider.stop()
                            locationProvider.hasPermission() -> locationProvider.start()
                            else -> locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }
                    }
            }
        }
    }
}

/** Layout check: top bar + bottom controls over a placeholder viewfinder (phone size). */
@androidx.compose.ui.tooling.preview.Preview(name = "Camera layout", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun CameraLayoutPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // 4:3 viewfinder placement as in CameraPreview (directly below the top bar)
            Box(
                Modifier
                    .padding(top = 28.dp + com.fearmikey.camera.ui.camera.TopBarHeight)
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color(0xFF556B55))
            )
            CameraTopBar(
                rotation = 0, showResolution = true, isFullResolution = false, showFlash = true,
                flashLabel = "Off", flashIcon = Icons.Default.FlashOff, flashActive = false,
                timerSeconds = 0, macroOn = false,
                onResolutionClick = {}, onFlashClick = {}, onTimerClick = {},
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp, start = 12.dp, end = 12.dp)
            )
            CameraControls(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                currentMode = CameraMode.PHOTO,
                captureType = CaptureType.PHOTO,
                isRecording = false, isPaused = false, isProcessing = false, isPanoramaCapturing = false,
                captureProgress = 0f, recordingDuration = "00:00", lastCapturedUri = null,
                orientation = 0, zoomLevel = 1f, zoomPresets = listOf(0.6f, 1f, 2f, 5f),
                onZoomChanged = {}, onModeSelected = {}, onCapture = {}, onPause = {}, onResume = {},
                onVideoSnapshot = {}, onQuickTakeStart = {}, onQuickTakeStop = {}, onThumbnailClick = {},
                onFlipCamera = {}, onTypeSelected = {}, onSettingsClick = {}
            )
        }
    }
}

/** Thumbnail of the most recent capture, loaded with the platform thumbnail API. */
@Composable
fun MediaThumbnail(uri: android.net.Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(192, 192), null).asImageBitmap()
            }.getOrNull()
        }
    }
    thumbnail?.let {
        Image(
            bitmap = it,
            contentDescription = "Last captured media",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val orientation by viewModel.orientationState.collectAsState()
    val haptic = LocalHapticFeedback.current
    
    var showFlashOverlay by remember { mutableStateOf(false) }
    
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] ?: hasCameraPermission
    }

    LaunchedEffect(key1 = true) {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            launcher.launch(needed.toTypedArray())
        }
    }

    if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                aspectRatio = uiState.userPreferences.aspectRatio,
                zoomLevel = uiState.zoomLevel,
                isFrontCamera = uiState.isFrontCamera,
                gridMode = uiState.userPreferences.gridMode,
                showLeveler = uiState.userPreferences.showLeveler,
                tiltFlow = viewModel.tiltState,
                autoShowLevelerFlow = viewModel.autoShowLeveler,
                focusFlow = viewModel.focusState,
                gestureFlow = viewModel.gestureState,
                onZoomChanged = { viewModel.setZoom(it) },
                onFocusAt = { x, y -> viewModel.focusAt(x, y) },
                exposureIndex = uiState.exposureIndex,
                exposureRange = uiState.capabilities?.exposureRange?.let { it.lower..it.upper } ?: 0..0,
                exposureStep = uiState.capabilities?.exposureStep ?: 0f,
                onExposureChange = { viewModel.setExposureIndex(it) },
                onDoubleTap = { if (uiState.userPreferences.doubleTapSwitch) viewModel.toggleCamera() },
                viewfinderOverlay = {
                    if (uiState.currentMode == CameraMode.PANORAMA) {
                        com.fearmikey.camera.ui.camera.PanoramaViewfinderGuide(
                            progress = uiState.panoramaProgress,
                            capturing = uiState.isPanoramaCapturing,
                            showIdleHint = uiState.backgroundTaskMessage == null,
                            // The corner preview hangs into the viewfinder (strip top is 8dp under
                            // the status bar, the viewfinder starts under the top bar).
                            pillTop = (8.dp + com.fearmikey.camera.ui.camera.PanoramaStripHeight + 12.dp -
                                com.fearmikey.camera.ui.camera.TopBarHeight).coerceAtLeast(20.dp),
                        )
                    }
                },
                onBindToLifecycle = { owner, provider, factoryProvider -> 
                    viewModel.bindToLifecycle(owner, provider, factoryProvider) 
                },
                onModeSwiped = { direction ->
                    val modes = if (uiState.captureType == CaptureType.PHOTO) CameraModes.photo else CameraModes.video

                    val currentIndex = modes.indexOf(uiState.currentMode)
                    val nextIndex = if (direction > 0) {
                        (currentIndex - 1).coerceAtLeast(0)
                    } else {
                        (currentIndex + 1).coerceAtMost(modes.size - 1)
                    }
                    if (nextIndex != currentIndex && nextIndex != -1) {
                        viewModel.setMode(modes[nextIndex])
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            )

            val prefs = uiState.userPreferences
            val isPhoto = uiState.captureType == CaptureType.PHOTO
            // Flash/torch exists only on the rear camera; Night/HDR/Panorama never fire it.
            val flashSupportedHere = !uiState.isFrontCamera && uiState.currentMode !in setOf(
                CameraMode.NIGHT_SIGHT, CameraMode.HDR, CameraMode.PANORAMA, CameraMode.SLOW_MO,
                CameraMode.TIME_LAPSE, CameraMode.LONG_EXPOSURE
            ) && !(uiState.currentMode == CameraMode.PHOTO && prefs.hdrPhoto)
            CameraTopBar(
                rotation = orientation,
                showResolution = isPhoto && !uiState.isFrontCamera && uiState.currentMode == CameraMode.PHOTO,
                isFullResolution = prefs.resolution == ResolutionMode.MP_50,
                showFlash = flashSupportedHere,
                flashLabel = prefs.lightMode.label,
                flashIcon = when (prefs.lightMode) {
                    LightMode.AUTO_NIGHT_SIGHT -> Icons.Default.FlashAuto
                    LightMode.FLASH -> Icons.Default.FlashOn
                    LightMode.NONE -> Icons.Default.FlashOff
                },
                flashActive = prefs.lightMode == LightMode.FLASH,
                timerSeconds = if (isPhoto) prefs.timer.seconds else 0,
                macroOn = prefs.macroFocus == com.fearmikey.camera.domain.model.MacroMode.ON && !uiState.isFrontCamera,
                onResolutionClick = {
                    viewModel.updateResolution(
                        if (prefs.resolution == ResolutionMode.MP_12) ResolutionMode.MP_50 else ResolutionMode.MP_12
                    )
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onFlashClick = { viewModel.cycleFlash() },
                onTimerClick = { viewModel.setShowSettingsSheet(true) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = systemBarsPadding.calculateTopPadding(), start = 12.dp, end = 12.dp)
                    .alpha(if (uiState.isPanoramaCapturing) 0f else 1f)
            )

            // Manual controls sit below the top bar so they never cover the shutter.
            if (uiState.isProMode) {
                ProControlOverlay(
                    uiState = uiState,
                    onConfigChange = { viewModel.updateProConfig(it) },
                    onReset = { viewModel.resetProConfig() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = systemBarsPadding.calculateTopPadding() + com.fearmikey.camera.ui.camera.TopBarHeight + 8.dp, start = 12.dp, end = 12.dp)
                )
            }

            CameraControls(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = systemBarsPadding.calculateBottomPadding()),
                currentMode = uiState.currentMode,
                captureType = uiState.captureType,
                isRecording = uiState.isRecording,
                isPaused = uiState.isPaused,
                isProcessing = uiState.isProcessing,
                isPanoramaCapturing = uiState.isPanoramaCapturing,
                captureProgress = uiState.captureProgress,
                recordingDuration = uiState.recordingDuration,
                lastCapturedUri = uiState.lastCapturedUri,
                orientation = orientation,
                zoomLevel = uiState.zoomLevel,
                zoomPresets = uiState.zoomPresets,
                onZoomChanged = { viewModel.setZoom(it) },
                onModeSelected = { viewModel.setMode(it) },
                onCapture = { 
                    viewModel.toggleCapture()
                    if (!uiState.isRecording && uiState.currentMode != CameraMode.VIDEO && uiState.currentMode != CameraMode.PANORAMA) {
                        showFlashOverlay = true
                    }
                },
                onPause = { viewModel.pauseVideo() },
                onResume = { viewModel.resumeVideo() },
                onVideoSnapshot = { viewModel.takePhotoDuringVideo() },
                onQuickTakeStart = { viewModel.quickTakeStart() },
                onQuickTakeStop = { viewModel.quickTakeStop() },
                onThumbnailClick = {
                    uiState.lastCapturedUri?.let { uri ->
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(context, "No gallery app installed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFlipCamera = { viewModel.toggleCamera() },
                onTypeSelected = { viewModel.setCaptureType(it) },
                onSettingsClick = { viewModel.setShowSettingsSheet(true) },
                onProControlsClick = { viewModel.toggleProMode() },
                isProMode = uiState.isProMode,
            )

            if (uiState.showSettingsSheet) {
                SettingsSheet(
                    userPreferences = uiState.userPreferences,
                    captureType = uiState.captureType,
                    isFrontCamera = uiState.isFrontCamera,
                    onDismiss = { viewModel.setShowSettingsSheet(false) },
                    onAspectRatioChange = { viewModel.updateAspectRatio(it) },
                    onTimerChange = { viewModel.updateTimer(it) },
                    onLightModeChange = { viewModel.updateLightMode(it) },
                    onMacroFocusChange = { viewModel.updateMacroFocus(it) },
                    onResolutionChange = { viewModel.updateResolution(it) },
                    onSaveModeChange = { viewModel.updateSaveMode(it) },
                    onVideoResolutionChange = { viewModel.updateVideoResolution(it) },
                    onVideoStabilizationToggle = { viewModel.toggleVideoStabilization() },
                    onVideoFrameRateChange = { viewModel.updateVideoFrameRate(it) },
                    onHdrToggle = { viewModel.updateHdrPhoto(it) },
                    onRecordAudioToggle = { viewModel.updateRecordAudio(it) },
                    onGridLinesToggle = { viewModel.toggleGridLines() },
                    onLevelerToggle = { viewModel.toggleLeveler() },
                    onLocationTaggingToggle = { viewModel.toggleLocationTagging() },
                    onFullSettingsClick = { 
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }
                )
            }

            // Flash Overlay (Screen Flash effect)
            if (showFlashOverlay) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                LaunchedEffect(Unit) {
                    delay(50.milliseconds)
                    showFlashOverlay = false
                }
            }

            // Full-screen progress only for the slow multi-frame pipelines; quick photos
            // just show a spinner on the thumbnail so the camera stays usable.
            val slowPipeline = uiState.currentMode in setOf(
                CameraMode.NIGHT_SIGHT, CameraMode.HDR, CameraMode.PORTRAIT, CameraMode.LONG_EXPOSURE
            ) || (uiState.currentMode == CameraMode.PHOTO && prefs.hdrPhoto && !uiState.isFrontCamera)
            if (uiState.isProcessing && slowPipeline) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CameraAccent)
                        Spacer(modifier = Modifier.height(8.dp))
                        val processingText = when (uiState.currentMode) {
                            CameraMode.HDR, CameraMode.PHOTO -> "Merging HDR exposures..."
                            CameraMode.NIGHT_SIGHT -> "Hold still... merging night shot"
                            CameraMode.LONG_EXPOSURE -> "Hold still... capturing long exposure"
                            CameraMode.PORTRAIT -> "Blurring background..."
                            CameraMode.PANORAMA -> "Stitching Panorama..."
                            else -> "Processing..."
                        }
                        Text(processingText, color = Color.White)
                    }
                }
            }

            // Suggestions under the top bar (QR code, low light).
            if (!uiState.isProMode && !uiState.isRecording) {
                com.fearmikey.camera.ui.camera.SuggestionChips(
                    qrCode = uiState.qrCode,
                    showNightSuggestion = uiState.lowLightSuggested && uiState.currentMode == CameraMode.PHOTO,
                    onQrClick = { text ->
                        com.fearmikey.camera.ui.camera.openQrPayload(context, text)
                        viewModel.dismissQrCode()
                    },
                    onQrDismiss = { viewModel.dismissQrCode() },
                    onNightClick = { viewModel.acceptNightSuggestion() },
                    onNightDismiss = { viewModel.dismissNightSuggestion() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = systemBarsPadding.calculateTopPadding() + com.fearmikey.camera.ui.camera.TopBarHeight + 8.dp)
                )
            }

            // Camera2-recorded modes can't feed the CameraX preview while recording.
            if (uiState.isRecording && CameraModes.isCamera2Video(uiState.currentMode)) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text(
                        if (uiState.currentMode == CameraMode.TIME_LAPSE) "Recording time lapse\nKeep the phone steady"
                        else "Recording slow motion",
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            uiState.timerCountdown?.let { countdown ->
                TimerCountdownOverlay(countdown = countdown)
            }

            // Panorama corner preview: live view before the sweep, growing mosaic during it.
            // Leftward sweeps grow from the right corner, rightward ones from the left.
            val panoramaProgress = uiState.panoramaProgress
            if (uiState.currentMode == CameraMode.PANORAMA && panoramaProgress?.preview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = systemBarsPadding.calculateTopPadding() + 8.dp, start = 12.dp, end = 12.dp),
                    contentAlignment = if (panoramaProgress.direction > 0) Alignment.TopStart else Alignment.TopEnd
                ) {
                    com.fearmikey.camera.ui.camera.PanoramaStrip(panoramaProgress)
                }
            }

            // Background Task Indicator (Top Center)
            uiState.backgroundTaskMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = systemBarsPadding.calculateTopPadding() + 60.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(24.dp),
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (message.contains("...")) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CameraAccent
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = message,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    } else {
        PermissionRequiredScreen(
            onRequest = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
        )
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: DomainAspectRatio,
    zoomLevel: Float,
    isFrontCamera: Boolean,
    gridMode: com.fearmikey.camera.domain.model.GridMode,
    showLeveler: Boolean,
    tiltFlow: StateFlow<Float>,
    autoShowLevelerFlow: StateFlow<Boolean>,
    focusFlow: StateFlow<FocusState?>,
    gestureFlow: StateFlow<com.fearmikey.camera.ui.camera.GestureOverlayState>,
    onZoomChanged: (Float) -> Unit,
    onFocusAt: (Float, Float) -> Unit,
    onBindToLifecycle: (androidx.lifecycle.LifecycleOwner, androidx.camera.core.Preview.SurfaceProvider, () -> androidx.camera.core.MeteringPointFactory?) -> Unit,
    onModeSwiped: (Float) -> Unit = {},
    exposureIndex: Int = 0,
    exposureRange: IntRange = 0..0,
    exposureStep: Float = 0f,
    onExposureChange: (Int) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    /** Drawn on top of the viewfinder, sized and centred to it (e.g. the panorama guide). */
    viewfinderOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Gesture lambdas live across recompositions, so read the latest values through state.
    val latestZoom by rememberUpdatedState(zoomLevel)
    val latestOnZoom by rememberUpdatedState(onZoomChanged)
    val latestOnSwipe by rememberUpdatedState(onModeSwiped)
    val latestOnFocus by rememberUpdatedState(onFocusAt)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)
    var totalDragX by remember { mutableFloatStateOf(0f) }
    val focusState by focusFlow.collectAsState()

    val ratioValue = remember(aspectRatio) {
        when (aspectRatio) {
            DomainAspectRatio.RATIO_4_3 -> 3f / 4f
            DomainAspectRatio.RATIO_16_9 -> 9f / 16f
            DomainAspectRatio.RATIO_FULL -> 0f 
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        latestOnZoom(latestZoom * zoom)
                        totalDragX = 0f
                    } else {
                        totalDragX += pan.x
                        if (abs(totalDragX) > 200) {
                            latestOnSwipe(totalDragX)
                            totalDragX = 0f
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        val previewWidth: androidx.compose.ui.unit.Dp
        val previewHeight: androidx.compose.ui.unit.Dp

        if (ratioValue > 0) {
            val constraintsRatio = maxWidth / maxHeight
            if (constraintsRatio > ratioValue) {
                previewHeight = maxHeight
                previewWidth = maxHeight * ratioValue
            } else {
                previewWidth = maxWidth
                previewHeight = maxWidth / ratioValue
            }
        } else {
            previewWidth = maxWidth
            previewHeight = maxHeight
        }
        // Cropped ratios start right under the top bar (like most camera apps) so the
        // shutter area below stays clear; full-screen fills everything.
        val topBarBottom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            com.fearmikey.camera.ui.camera.TopBarHeight
        val previewTop = if (ratioValue > 0) {
            topBarBottom.coerceAtMost((maxHeight - previewHeight).coerceAtLeast(0.dp))
        } else 0.dp

        Box(
            modifier = Modifier
                .offset(y = previewTop)
                .size(previewWidth, previewHeight)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { latestOnDoubleTap() },
                        onTap = { offset -> latestOnFocus(offset.x / size.width, offset.y / size.height) }
                    )
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        onBindToLifecycle(lifecycleOwner, this.surfaceProvider) { this.meteringPointFactory }
                    }
                },
                update = {
                }
            )
            
            HudOverlay(
                gridMode = gridMode,
                showLeveler = showLeveler,
                tiltFlow = tiltFlow,
                autoShowLevelerFlow = autoShowLevelerFlow,
                focusFlow = focusFlow,
                gestureFlow = gestureFlow,
                isFrontCamera = isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )
            viewfinderOverlay()

            // Brightness slider beside the focus ring (flips to the left near the right edge).
            focusState?.let { focus ->
                if (exposureRange.first < exposureRange.last) {
                    val ringX = previewWidth * focus.point.x
                    val ringY = previewHeight * focus.point.y
                    val sliderX = if (focus.point.x > 0.75f) ringX - 100.dp else ringX + 52.dp
                    val sliderY = (ringY - 90.dp).coerceIn(0.dp, (previewHeight - 170.dp).coerceAtLeast(0.dp))
                    ExposureSlider(
                        index = exposureIndex,
                        range = exposureRange,
                        stepEv = exposureStep,
                        onIndexChange = onExposureChange,
                        modifier = Modifier.offset(x = sliderX, y = sliderY)
                    )
                }
            }
        }
    }
}

@Composable
fun TimerCountdownOverlay(
    countdown: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val scale by animateFloatAsState(
            targetValue = 1.3f,
            animationSpec = repeatable(
                iterations = countdown,
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "countdownScale"
        )
        
        Text(
            text = countdown.toString(),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 160.sp,
                fontWeight = FontWeight.Black
            ),
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale
            )
        )
    }
}

@Composable
fun RotatableIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    targetRotation: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    val rotation by animateFloatAsState(
        targetValue = targetRotation.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconRotation"
    )
    
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.rotate(rotation),
        tint = tint
    )
}

@Composable
fun CameraModeSelector(
    modes: List<CameraMode>,
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Start with the selected mode near the centre (refined by the effect below).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = modes.indexOf(currentMode).coerceAtLeast(0))
    
    // Keep the selected mode centred under the shutter.
    LaunchedEffect(currentMode, modes) {
        val index = modes.indexOf(currentMode)
        if (index == -1) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            listState.animateScrollToItem(index)
        } else {
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val delta = item.offset + item.size / 2 - viewportCenter
            if (delta != 0) listState.animateScrollBy(delta.toFloat())
        }
    }

    // Half-screen padding lets the first and last modes scroll to the centre too.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val sidePadding = (maxWidth / 2 - 48.dp).coerceAtLeast(0.dp)
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = modes,
            key = { it.name }
        ) { mode ->
            val isSelected = mode == currentMode
            val label = CameraModes.label(mode)
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White else Color.Transparent)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
    }
}

@Composable
fun PhotoVideoToggle(
    currentType: CaptureType,
    onTypeSelected: (CaptureType) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkGray = com.fearmikey.camera.ui.camera.ControlBackground
    Surface(
        color = darkGray,
        shape = CircleShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(if (currentType == CaptureType.PHOTO) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onTypeSelected(CaptureType.PHOTO) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Photo",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(if (currentType == CaptureType.VIDEO) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onTypeSelected(CaptureType.VIDEO) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ZoomSwitcher(
    currentZoom: Float,
    zoomPresets: List<Float>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The active pill is the largest preset at or below the current zoom; it shows
            // the live value (e.g. "3.4x") while pinching between presets.
            val activePreset = zoomPresets.lastOrNull { it <= currentZoom + 0.05f } ?: zoomPresets.firstOrNull()
            zoomPresets.forEach { zoom ->
                val isSelected = zoom == activePreset
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { onZoomSelected(zoom) },
                    contentAlignment = Alignment.Center
                ) {
                    val label = if (isSelected) formatZoom(currentZoom) else formatZoom(zoom)
                    Text(
                        text = label,
                        color = if (isSelected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun CameraControls(
    modifier: Modifier = Modifier,
    currentMode: CameraMode,
    captureType: CaptureType,
    isRecording: Boolean,
    isPaused: Boolean,
    isProcessing: Boolean,
    isPanoramaCapturing: Boolean,
    captureProgress: Float,
    recordingDuration: String,
    lastCapturedUri: android.net.Uri?,
    orientation: Int,
    zoomLevel: Float,
    zoomPresets: List<Float>,
    onZoomChanged: (Float) -> Unit,
    onModeSelected: (CameraMode) -> Unit,
    onCapture: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onVideoSnapshot: () -> Unit,
    onQuickTakeStart: () -> Unit,
    onQuickTakeStop: () -> Unit,
    onThumbnailClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onTypeSelected: (CaptureType) -> Unit,
    onSettingsClick: () -> Unit,
    onProControlsClick: () -> Unit = {},
    isProMode: Boolean = false,
) {
    val currentModes = if (captureType == CaptureType.PHOTO) CameraModes.photo else CameraModes.video
    val darkGray = com.fearmikey.camera.ui.camera.ControlBackground
    // During a panorama sweep only the shutter stays; everything else fades out in place
    // (layout keeps its size so the shutter doesn't jump).
    val idle = !isPanoramaCapturing
    val chromeAlpha by animateFloatAsState(if (idle) 1f else 0f, label = "panoChrome")

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ZoomSwitcher(
            currentZoom = zoomLevel,
            zoomPresets = zoomPresets,
            onZoomSelected = { if (idle) onZoomChanged(it) },
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).alpha(chromeAlpha)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Button: Thumbnail or Pause/Resume
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .alpha(chromeAlpha)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(darkGray)
                    .clickable(enabled = idle) { 
                        if (isRecording) {
                            if (isPaused) onResume() else onPause()
                        } else {
                            onThumbnailClick()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    lastCapturedUri?.let { uri ->
                        MediaThumbnail(uri = uri, modifier = Modifier.fillMaxSize())
                    }
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = CameraAccent
                        )
                    }
                }
            }

            // Shutter
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(92.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isProcessing && currentMode in setOf(CameraMode.NIGHT_SIGHT, CameraMode.HDR, CameraMode.LONG_EXPOSURE)) {
                        val progressAnim by animateFloatAsState(
                            targetValue = captureProgress,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "shutterProgress"
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.2f),
                                style = Stroke(width = 4.dp.toPx())
                            )
                            drawArc(
                                color = Color.White,
                                startAngle = -90f,
                                sweepAngle = progressAnim * 360f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }

                    val isVideoMode = CameraModes.isVideo(currentMode)
                    val isPanorama = currentMode == CameraMode.PANORAMA
                    val shutterColor = when {
                        // Dark disc; translucent white so it still reads on the black control area.
                        isPanoramaCapturing -> Color.White.copy(alpha = 0.16f)
                        isVideoMode || isRecording -> Color.Red
                        else -> Color.White
                    }
                    val shutterInnerShape = if (isRecording) androidx.compose.foundation.shape.RoundedCornerShape(12.dp) else CircleShape

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(shutterInnerShape)
                            .background(shutterColor)
                            .border(2.dp, Color.Black.copy(alpha = 0.1f), shutterInnerShape)
                            .pointerInput(isPanorama) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    val result = withTimeoutOrNull(500) {
                                        waitForUpOrCancellation()
                                    }
                                    if (result == null && !isPanorama) {
                                        onQuickTakeStart()
                                        waitForUpOrCancellation()
                                        onQuickTakeStop()
                                    } else {
                                        onCapture()
                                    }
                                }
                            }
                    ) {
                        if (isPanoramaCapturing) {
                            // Stop: white rounded square on a dark translucent disc.
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            )
                        } else if (isPanorama) {
                            com.fearmikey.camera.ui.camera.PanoramaShutterGlyph()
                        }
                    }

                    // Outer white ring for non-recording photo mode
                    if (!isRecording && !isVideoMode && !isPanoramaCapturing) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .border(4.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        )
                    }
                }
                
                if (isRecording) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.Red,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = recordingDuration,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Right Button: Flip Camera or Video Snapshot
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .alpha(chromeAlpha)
                    .clip(CircleShape)
                    .background(darkGray)
                    .clickable(enabled = idle) { if (isRecording) onVideoSnapshot() else onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    // White solid circle for photo during video
                    Box(modifier = Modifier.size(56.dp).background(Color.White, CircleShape))
                } else {
                    RotatableIcon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "Switch Camera",
                        targetRotation = orientation,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Modes below shutter
        CameraModeSelector(
            modes = currentModes,
            currentMode = currentMode,
            onModeSelected = { if (idle) onModeSelected(it) },
            modifier = Modifier.padding(bottom = 12.dp).alpha(chromeAlpha)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
                .alpha(chromeAlpha),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.fearmikey.camera.ui.camera.RotatingIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                rotation = orientation,
                onClick = { if (idle) onSettingsClick() },
            )

            PhotoVideoToggle(
                currentType = captureType,
                onTypeSelected = { if (idle) onTypeSelected(it) }
            )

            com.fearmikey.camera.ui.camera.RotatingIconButton(
                icon = Icons.Default.Tune,
                contentDescription = "Manual controls",
                rotation = orientation,
                onClick = { if (idle) onProControlsClick() },
                tint = if (isProMode) com.fearmikey.camera.ui.camera.OnCameraAccent else Color.White,
                background = if (isProMode) CameraAccent else darkGray,
            )
        }
    }
}
