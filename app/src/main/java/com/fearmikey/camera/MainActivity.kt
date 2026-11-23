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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.CameraModes
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.LightMode
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NightsStay
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
        
        // Only the *_SECURE actions come from the lock screen. Plain STILL_IMAGE_CAMERA is what the
        // power-button gesture sends while unlocked and must open the normal, unrestricted camera.
        if ((intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE) ||
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
        viewModel = CameraViewModel(
            hybridCaptureEngine, mediaRepository, userPreferencesRepository, secureSession = isSecureCamera,
        )

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        viewModel.initSensors(sensorManager)
        observeLocationSetting()

        setContent {
            com.fearmikey.camera.ui.theme.ViewfinderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    CameraScreen(viewModel, isSecureCamera = isSecureCamera)
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
                captureProgress = 0f, lastCapturedUri = null,
                orientation = 0,
                onModeSelected = {}, onCapture = {}, onPause = {}, onResume = {},
                onVideoSnapshot = {}, onQuickTakeStart = {}, onQuickTakeStop = {}, onThumbnailClick = {},
                onFlipCamera = {}, onTypeSelected = {}, onSettingsClick = {}
            )
            val viewfinderBottom = with(LocalDensity.current) {
                (28.dp + com.fearmikey.camera.ui.camera.TopBarHeight + 393.dp * 4 / 3 - ZoomControlsGap).toPx()
            }
            ViewfinderZoomControls(
                anchorBottom = { viewfinderBottom },
                zoomLevel = { 1f },
                zoomPresets = listOf(0.5f, 1f, 2f, 5f),
                onZoomChanged = {},
                visible = true,
                modifier = Modifier.align(Alignment.TopCenter),
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
    viewModel: CameraViewModel,
    /** Launched over the lock screen: no gallery access. */
    isSecureCamera: Boolean = false,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val orientation by viewModel.orientationState.collectAsState()
    // Per-frame state (zoom while pinching/gliding, panorama progress). Deliberately not read
    // here: only the children that read .value recompose on their updates, not this screen.
    val zoomState = viewModel.zoomState.collectAsState()
    val panoramaProgress = viewModel.panoramaProgress.collectAsState()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // Live code outlines are mapped onto this view (see CodeOutlineOverlay).
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Scan a QR code/barcode from a picked image (Photo Picker: no storage permission needed).
    val pickImageForScan = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.scanCodesInImage(context.contentResolver, it) }
    }
    val scanImage: (() -> Unit)? = if (isSecureCamera) null else {
        { pickImageForScan.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    // Over the lock screen, anything that leaves the camera (gallery, full settings) asks the
    // user to unlock first and only opens once they have.
    val openOutside: (Intent) -> Unit = { intent ->
        val start = {
            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No app found to open this", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val activity = context as? android.app.Activity
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (isSecureCamera && activity != null && keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(activity, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = start()
            })
        } else {
            start()
        }
    }

    // Short confirmation tick whenever a new code shows up.
    var knownCodes by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(uiState.scannedCodes) {
        val texts = uiState.scannedCodes.mapTo(HashSet()) { it.text }
        if ((texts - knownCodes).isNotEmpty()) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        knownCodes = texts
    }
    
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

    // Zoom buttons float just above whichever comes first: the viewfinder's bottom edge or the
    // shutter controls (16:9 / full-screen viewfinders run underneath them). Root coordinates.
    var rootTop by remember { mutableFloatStateOf(0f) }
    var viewfinderBottom by remember { mutableFloatStateOf(Float.NaN) }
    var controlsTop by remember { mutableFloatStateOf(Float.NaN) }
    val zoomGapPx = with(LocalDensity.current) { ZoomControlsGap.toPx() }

    if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { rootTop = it.positionInRoot().y }) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onViewfinderBottom = { viewfinderBottom = it },
                onViewfinderAspect = { viewModel.onViewfinderAspect(it) },
                aspectRatio = uiState.userPreferences.aspectRatio,
                // The flow's value, not the Compose state: it is current even between frames, so
                // several pinch events in one frame each build on the previous one.
                zoomLevel = { viewModel.zoomState.value.level },
                isFrontCamera = uiState.isFrontCamera,
                gridMode = uiState.userPreferences.gridMode,
                showLeveler = uiState.userPreferences.showLeveler,
                levelFlow = viewModel.levelState,
                focusFlow = viewModel.focusState,
                gestureFlow = viewModel.gestureState,
                onZoomChanged = { viewModel.setZoom(it) },
                onFocusAt = { x, y -> viewModel.focusAt(x, y) },
                exposureIndex = uiState.exposureIndex,
                exposureRange = remember(uiState.capabilities?.exposureRange) {
                    uiState.capabilities?.exposureRange?.let { it.lower..it.upper } ?: 0..0
                },
                exposureStep = uiState.capabilities?.exposureStep ?: 0f,
                onExposureChange = { viewModel.setExposureIndex(it) },
                // Gesture callbacks read the latest state when they fire instead of capturing
                // uiState, so they stay the same instance and CameraPreview can skip recomposing.
                onDoubleTap = { if (viewModel.uiState.value.userPreferences.doubleTapSwitch) viewModel.toggleCamera() },
                onPreviewViewReady = { previewView = it },
                viewfinderOverlay = {
                    if (uiState.currentMode == CameraMode.PHOTO && uiState.userPreferences.scanQrCodes) {
                        com.fearmikey.camera.ui.camera.CodeOutlineOverlay(
                            frameFlow = viewModel.codeScanFrame,
                            previewView = previewView,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (uiState.currentMode == CameraMode.PORTRAIT && !uiState.isProcessing) {
                        com.fearmikey.camera.ui.camera.PortraitFaceOverlay(
                            frameFlow = viewModel.portraitFaces,
                            previewView = previewView,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (uiState.currentMode == CameraMode.PANORAMA) {
                        ReadInOwnScope(panoramaProgress) { progress ->
                        com.fearmikey.camera.ui.camera.PanoramaViewfinderGuide(
                            progress = progress,
                            capturing = uiState.isPanoramaCapturing,
                            showIdleHint = uiState.backgroundTaskMessage == null,
                            // The corner preview hangs into the viewfinder (strip top is 8dp under
                            // the status bar, the viewfinder starts under the top bar).
                            pillTop = (8.dp + com.fearmikey.camera.ui.camera.PanoramaStripHeight + 12.dp -
                                com.fearmikey.camera.ui.camera.TopBarHeight).coerceAtLeast(20.dp),
                        )
                        }
                    }
                },
                onBindToLifecycle = { owner, provider, factoryProvider -> 
                    viewModel.bindToLifecycle(owner, provider, factoryProvider) 
                },
                onModeSwiped = { direction ->
                    val state = viewModel.uiState.value
                    // Modes are locked while recording or sweeping (setMode would refuse anyway).
                    if (state.isRecording || state.isPanoramaCapturing) return@CameraPreview
                    val modes = if (state.captureType == CaptureType.PHOTO) CameraModes.photo else CameraModes.video

                    val currentIndex = modes.indexOf(state.currentMode)
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
            // What this mode/camera/settings combination supports (shared with the engine router).
            val caps = uiState.modeCaps
            val lightMode = caps.effectiveLightMode
            CameraTopBar(
                rotation = orientation,
                showResolution = caps.showsResolutionChip,
                isFullResolution = prefs.resolution == ResolutionMode.MP_50,
                showFlash = lightMode != null,
                flashLabel = when {
                    caps.flashIsTorch -> if (lightMode == LightMode.FLASH) "torch on" else "torch off"
                    else -> lightMode?.label ?: ""
                },
                flashIcon = when (lightMode) {
                    LightMode.AUTO_NIGHT_SIGHT -> Icons.Default.NightsStay
                    LightMode.AUTO_FLASH -> Icons.Default.FlashAuto
                    LightMode.FLASH -> Icons.Default.FlashOn
                    LightMode.NONE, null -> Icons.Default.FlashOff
                },
                // Accent when the flash will fire, or Auto Night will take the next shot.
                flashActive = lightMode == LightMode.FLASH || caps.autoNightActive,
                timerSeconds = if (caps.supportsTimer) prefs.timer.seconds else 0,
                macroOn = prefs.macroFocus == com.fearmikey.camera.domain.model.MacroMode.ON && caps.showsMacro,
                onResolutionClick = {
                    viewModel.updateResolution(
                        if (prefs.resolution == ResolutionMode.MP_12) ResolutionMode.MP_50 else ResolutionMode.MP_12
                    )
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onFlashClick = { viewModel.cycleFlash() },
                onTimerClick = { viewModel.setShowSettingsSheet(true) },
                chipsEnabled = !uiState.isRecording,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = systemBarsPadding.calculateTopPadding(), start = 12.dp, end = 12.dp)
                    .alpha(if (uiState.isPanoramaCapturing) 0f else 1f),
                onScanImageClick = scanImage.takeIf {
                    uiState.currentMode == CameraMode.PHOTO && prefs.scanQrCodes && !uiState.isRecording
                },
                isScanningImage = uiState.isScanningImage,
            )

            // Manual controls sit below the top bar so they never cover the shutter.
            if (uiState.isProMode) {
                ProControlOverlay(
                    uiState = uiState,
                    onConfigChange = { viewModel.updateProConfig(it) },
                    onReset = { viewModel.resetProConfig() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                    // Leave room for the recording time pill while recording.
                    top = systemBarsPadding.calculateTopPadding() + com.fearmikey.camera.ui.camera.TopBarHeight + 8.dp +
                        (if (uiState.isRecording) 44.dp else 0.dp),
                    start = 12.dp, end = 12.dp
                )
                )
            }

            CameraControls(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = systemBarsPadding.calculateBottomPadding())
                    .onGloballyPositioned { controlsTop = it.positionInRoot().y },
                currentMode = uiState.currentMode,
                captureType = uiState.captureType,
                isRecording = uiState.isRecording,
                isPaused = uiState.isPaused,
                isProcessing = uiState.isProcessing,
                isPanoramaCapturing = uiState.isPanoramaCapturing,
                captureProgress = uiState.captureProgress,
                nightShutter = caps.autoNightActive && !uiState.isProcessing,
                showCaptureProgress = uiState.isProcessing && uiState.showsCaptureProgress,
                supportsQuickTake = caps.supportsQuickTake,
                supportsVideoSnapshot = caps.supportsVideoSnapshot,
                manualControlsAvailable = caps.supportsManualControls,
                lastCapturedUri = uiState.lastCapturedUri,
                orientation = orientation,
                onModeSelected = { viewModel.setMode(it) },
                onCapture = {
                    // Latest state at tap time (not captured), so this lambda stays stable.
                    val state = viewModel.uiState.value
                    val tapCaps = state.modeCaps
                    // Screen flash only when this tap takes a photo right away (not a video
                    // mode, a panorama sweep, a timer start/cancel or a refused tap mid-processing).
                    val photoNow = !tapCaps.isVideo && state.currentMode != CameraMode.PANORAMA &&
                        !state.isProcessing && state.timerCountdown == null &&
                        !(tapCaps.supportsTimer && state.userPreferences.timer != com.fearmikey.camera.domain.model.TimerMode.OFF)
                    viewModel.toggleCapture()
                    if (photoNow) showFlashOverlay = true
                },
                onPause = { viewModel.pauseVideo() },
                onResume = { viewModel.resumeVideo() },
                onVideoSnapshot = { viewModel.takePhotoDuringVideo() },
                onQuickTakeStart = { viewModel.quickTakeStart() },
                onQuickTakeStop = { viewModel.quickTakeStop() },
                onThumbnailClick = {
                    viewModel.uiState.value.lastCapturedUri?.let { uri ->
                        openOutside(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    }
                },
                onFlipCamera = { viewModel.toggleCamera() },
                onTypeSelected = { viewModel.setCaptureType(it) },
                onSettingsClick = { viewModel.setShowSettingsSheet(true) },
                onProControlsClick = { viewModel.toggleProMode() },
                isProMode = uiState.isProMode,
            )

            ViewfinderZoomControls(
                anchorBottom = {
                    val vf = viewfinderBottom
                    val ct = controlsTop
                    if (vf.isNaN() || ct.isNaN()) Float.NaN else minOf(vf, ct) - rootTop - zoomGapPx
                },
                zoomLevel = { zoomState.value.level },
                zoomPresets = uiState.zoomPresets,
                // Presets glide to the new zoom (pinch stays immediate via setZoom).
                onZoomChanged = { viewModel.animateZoomTo(it) },
                zoomTarget = { zoomState.value.target },
                // During a panorama sweep only the shutter stays; Camera2 recordings (slow
                // motion, time lapse) fix the zoom when they start.
                visible = !uiState.isPanoramaCapturing && !(uiState.isRecording && !caps.supportsZoomWhileRecording),
                portraitAperture = prefs.portraitAperture.takeIf {
                    uiState.currentMode == CameraMode.PORTRAIT && !uiState.isRecording
                },
                onPortraitApertureChange = { viewModel.updatePortraitAperture(it) },
                zoomLabelScale = uiState.zoomLabelScale,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (uiState.showSettingsSheet) {
                SettingsSheet(
                    userPreferences = uiState.userPreferences,
                    modeCaps = caps,
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
                    onGridModeChange = { viewModel.updateGridMode(it) },
                    onLevelerToggle = { viewModel.toggleLeveler() },
                    onLocationTaggingToggle = { viewModel.toggleLocationTagging() },
                    onFullSettingsClick = {
                        viewModel.setShowSettingsSheet(false)
                        openOutside(Intent(context, SettingsActivity::class.java))
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
            // (uiState.capturePipeline is the routing snapshotted at the shutter press.)
            val runningPipeline = uiState.capturePipeline
            if (uiState.isProcessing && runningPipeline != null && runningPipeline.showsProcessingOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Determinate once the engine reports progress (Portrait reports none).
                        if (uiState.showsCaptureProgress && uiState.captureProgress > 0f) {
                            val shown by animateFloatAsState(uiState.captureProgress, label = "overlayProgress")
                            CircularProgressIndicator(progress = { shown }, color = CameraAccent)
                        } else {
                            CircularProgressIndicator(color = CameraAccent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val processingText = when (runningPipeline) {
                            com.fearmikey.camera.domain.model.CapturePipeline.HDR -> "Merging HDR exposures..."
                            com.fearmikey.camera.domain.model.CapturePipeline.NIGHT -> "Hold still... merging night shot"
                            com.fearmikey.camera.domain.model.CapturePipeline.LONG_EXPOSURE -> "Hold still... capturing long exposure"
                            com.fearmikey.camera.domain.model.CapturePipeline.PORTRAIT -> "Blurring background..."
                            else -> "Processing..."
                        }
                        Text(processingText, color = Color.White)
                    }
                }
            }

            // Suggestions under the top bar (scanned codes, low light).
            if (!uiState.isProMode && !uiState.isRecording && uiState.codeSheet == null) {
                com.fearmikey.camera.ui.camera.SuggestionChips(
                    codes = uiState.scannedCodes,
                    // Auto Night handles dark scenes by itself, so it never shows the chip.
                    showNightSuggestion = uiState.lowLightSuggested && uiState.currentMode == CameraMode.PHOTO &&
                        prefs.lightMode != LightMode.AUTO_NIGHT_SIGHT,
                    onCodesClick = { viewModel.openScannedCodes() },
                    onCodesDismiss = { viewModel.dismissScannedCodes() },
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

            // Recording time at the top of the viewfinder (keeps the shutter row fixed).
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isRecording,
                enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(tween(260)) { -it / 2 },
                exit = fadeOut(tween(160)) + androidx.compose.animation.slideOutVertically(tween(200)) { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = systemBarsPadding.calculateTopPadding() + com.fearmikey.camera.ui.camera.TopBarHeight + 8.dp)
            ) {
                com.fearmikey.camera.ui.camera.RecordingTimerPill(
                    duration = uiState.recordingDuration,
                    isPaused = uiState.isPaused,
                )
            }

            uiState.timerCountdown?.let { countdown ->
                TimerCountdownOverlay(countdown = countdown)
            }

            uiState.codeSheet?.let { codes ->
                com.fearmikey.camera.ui.camera.CodeResultSheet(
                    codes = codes,
                    onDismiss = { viewModel.closeCodeSheet() },
                    onScanImage = scanImage,
                )
            }

            // Panorama corner preview: live view before the sweep, growing mosaic during it.
            // Leftward sweeps grow from the right corner, rightward ones from the left.
            if (uiState.currentMode == CameraMode.PANORAMA) {
                ReadInOwnScope(panoramaProgress) { progress ->
                    if (progress?.preview != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = systemBarsPadding.calculateTopPadding() + 8.dp, start = 12.dp, end = 12.dp),
                            contentAlignment = if (progress.direction > 0) Alignment.TopStart else Alignment.TopEnd
                        ) {
                            com.fearmikey.camera.ui.camera.PanoramaStrip(progress)
                        }
                    }
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

/**
 * Reads [state] inside its own recompose scope, so its (frequent) updates re-run only [content]
 * instead of the caller. Used for the per-frame panorama progress.
 */
@Composable
private fun <T> ReadInOwnScope(state: State<T>, content: @Composable (T) -> Unit) {
    content(state.value)
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: DomainAspectRatio,
    /** Current zoom ratio, read when a pinch event arrives. */
    zoomLevel: () -> Float,
    isFrontCamera: Boolean,
    gridMode: com.fearmikey.camera.domain.model.GridMode,
    showLeveler: Boolean,
    levelFlow: StateFlow<com.fearmikey.camera.ui.camera.LevelState>,
    focusFlow: StateFlow<FocusState?>,
    gestureFlow: StateFlow<com.fearmikey.camera.ui.camera.GestureOverlayState>,
    onZoomChanged: (Float) -> Unit,
    onFocusAt: (Float, Float) -> Unit,
    onBindToLifecycle: (androidx.lifecycle.LifecycleOwner, androidx.camera.core.Preview.SurfaceProvider, com.fearmikey.camera.data.engine.MeteringPointProvider) -> Unit,
    onModeSwiped: (Float) -> Unit = {},
    exposureIndex: Int = 0,
    exposureRange: IntRange = 0..0,
    exposureStep: Float = 0f,
    onExposureChange: (Int) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    /** Receives the PreviewView once it is created (for mapping analysis coordinates onto it). */
    onPreviewViewReady: (PreviewView) -> Unit = {},
    /** Drawn on top of the viewfinder, sized and centered to it (e.g. the panorama guide). */
    viewfinderOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** Bottom edge of the visible viewfinder in root coordinates (px), for anchoring the zoom row. */
    onViewfinderBottom: (Float) -> Unit = {},
    /** Visible viewfinder width / height, so photos can be cropped to what it shows. */
    onViewfinderAspect: (Float) -> Unit = {},
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

    // While the camera rebinds (camera flip, Portrait/Panorama, returning from a Night/HDR
    // burst) PreviewView would freeze or go black. Hold a blurred copy of the last frame and
    // cross-fade it away once frames flow again.
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var heldFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var streaming by remember { mutableStateOf(false) }
    val heldAlpha = remember { Animatable(0f) }
    DisposableEffect(previewViewRef, lifecycleOwner) {
        val view = previewViewRef ?: return@DisposableEffect onDispose {}
        val observer = androidx.lifecycle.Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                streaming = true
            } else {
                streaming = false
                if (heldFrame == null) heldFrame = snapshotForTransition(view)
            }
        }
        view.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { view.previewStreamState.removeObserver(observer) }
    }
    LaunchedEffect(heldFrame, streaming) {
        if (heldFrame == null) return@LaunchedEffect
        if (!streaming) {
            heldAlpha.snapTo(1f)
        } else {
            heldAlpha.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            heldFrame = null
        }
    }

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
                        latestOnZoom(latestZoom() * zoom)
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
        val latestOnAspect by rememberUpdatedState(onViewfinderAspect)
        LaunchedEffect(previewWidth, previewHeight) {
            if (previewWidth > 0.dp && previewHeight > 0.dp) latestOnAspect(previewWidth / previewHeight)
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
                .onGloballyPositioned { onViewfinderBottom(it.positionInRoot().y + it.size.height) }
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
                        previewViewRef = this
                        // PreviewView's factory takes view pixels (and handles crop/rotation);
                        // taps arrive normalized to the viewfinder, which this view fills.
                        val view = this
                        onBindToLifecycle(lifecycleOwner, this.surfaceProvider) { nx, ny, size ->
                            if (view.width > 0 && view.height > 0) {
                                view.meteringPointFactory.createPoint(nx * view.width, ny * view.height, size)
                            } else null
                        }
                        onPreviewViewReady(this)
                    }
                },
                update = {
                }
            )

            heldFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = heldAlpha.value }
                        .blur(20.dp)
                )
            }

            HudOverlay(
                gridMode = gridMode,
                showLeveler = showLeveler,
                levelFlow = levelFlow,
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
            // Scale is read in the draw phase: the pulse doesn't recompose every frame.
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
        // Draw-phase rotation: turning the phone doesn't recompose the icon every frame.
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        tint = tint
    )
}

/**
 * Small, blurred-ready copy of the current viewfinder frame for [CameraPreview]'s rebind
 * cross-fade (quarter size: it is shown blurred, so detail is irrelevant). Null if no frame.
 */
private fun snapshotForTransition(view: PreviewView): ImageBitmap? = try {
    view.bitmap?.let { full ->
        val small = android.graphics.Bitmap.createScaledBitmap(
            full, (full.width / 4).coerceAtLeast(1), (full.height / 4).coerceAtLeast(1), true
        )
        if (small !== full) full.recycle()
        small.asImageBitmap()
    }
} catch (e: Exception) {
    null
}

@Composable
fun CameraModeSelector(
    modes: List<CameraMode>,
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Photo <-> Video swaps the whole row: cross-fade instead of popping the labels.
    // Each row keeps its own scroll state so the outgoing one doesn't jump while fading.
    AnimatedContent(
        targetState = modes,
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
        label = "modeRow",
        modifier = modifier.fillMaxWidth(),
    ) { rowModes ->
        ModeCarousel(rowModes, currentMode, enabled, onModeSelected)
    }
}

/** A visible carousel item and its signed distance (px) from the viewport center. */
private class ModeSnapTarget(val index: Int, val distance: Float)

/**
 * The visible item whose center is closest to [projected] px past the viewport center
 * (0 = the item currently under the center).
 */
private fun LazyListState.modeSnapTarget(projected: Float = 0f): ModeSnapTarget? {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo
        .map { ModeSnapTarget(it.index, it.offset + it.size / 2f - center) }
        .minByOrNull { abs(it.distance - projected) }
}

@Composable
private fun ModeCarousel(
    modes: List<CameraMode>,
    currentMode: CameraMode,
    enabled: Boolean,
    onModeSelected: (CameraMode) -> Unit,
) {
    // Start with the selected mode near the center (refined by the effect below).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = modes.indexOf(currentMode).coerceAtLeast(0))
    var centered by remember { mutableStateOf(false) }
    val view = LocalView.current
    val latestModes by rememberUpdatedState(modes)
    val latestMode by rememberUpdatedState(currentMode)
    val latestOnSelected by rememberUpdatedState(onModeSelected)

    // While the user drags (or the row settles after release) the pill follows whichever mode
    // is under the center; otherwise it shows the committed mode.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var settling by remember { mutableStateOf(false) }
    // The mode a swipe just selected, held until currentMode catches up (the ViewModel's
    // StateFlow reaches us a frame or so later) so the pill doesn't flash back to the old mode.
    var pendingMode by remember { mutableStateOf<CameraMode?>(null) }
    LaunchedEffect(currentMode) { pendingMode = null }
    LaunchedEffect(pendingMode) {
        // Safety net: if the selection was refused, fall back to the real mode.
        if (pendingMode != null) {
            kotlinx.coroutines.delay(1_000)
            pendingMode = null
        }
    }
    val centeredIndex by remember { derivedStateOf { listState.modeSnapTarget()?.index } }
    val tracking = isDragged || settling
    val highlighted = when {
        tracking -> centeredIndex?.let { modes.getOrNull(it) } ?: currentMode
        else -> pendingMode ?: currentMode
    }
    LaunchedEffect(highlighted) {
        if (tracking) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // On release, glide to the mode nearest where the fling would have stopped, center it and
    // select it.
    val decay = rememberSplineBasedDecay<Float>()
    val flingBehavior = remember(listState, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                settling = true
                try {
                    val projected = decay.calculateTargetValue(0f, initialVelocity)
                    val target = listState.modeSnapTarget(projected) ?: return initialVelocity
                    var previous = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = target.distance,
                        initialVelocity = initialVelocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { value, _ ->
                        scrollBy(value - previous)
                        previous = value
                    }
                    val landed = latestModes.getOrNull(target.index)
                    if (landed != null && landed != latestMode) {
                        pendingMode = landed
                        latestOnSelected(landed)
                    }
                } finally {
                    settling = false
                }
                return 0f
            }
        }
    }

    // Keep the selected mode centered under the shutter: snap on first layout, glide afterwards.
    LaunchedEffect(currentMode, modes) {
        val index = modes.indexOf(currentMode)
        if (index == -1) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            // Off-screen: bring it in first (lands with its start at the center), then center it.
            if (centered) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val delta = item.offset + item.size / 2 - viewportCenter
            if (delta != 0) {
                if (centered) {
                    listState.animateScrollBy(
                        delta.toFloat(),
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    )
                } else {
                    listState.scrollBy(delta.toFloat())
                }
            }
        }
        centered = true
    }

    // Half-screen padding lets every mode (even short first/last labels) reach the center.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val sidePadding = maxWidth / 2
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        flingBehavior = flingBehavior,
        userScrollEnabled = enabled,
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = modes,
            key = { it.name }
        ) { mode ->
            val isSelected = mode == highlighted
            val label = CameraModes.label(mode)
            val pillColor by animateColorAsState(
                if (isSelected) Color.White else Color.White.copy(alpha = 0f),
                tween(200), label = "modePill"
            )
            val labelColor by animateColorAsState(
                if (isSelected) Color.Black else Color.White,
                tween(200), label = "modeLabel"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(pillColor)
                    .clickable(enabled = enabled) { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = labelColor,
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
    val segmentWidth = 52.dp
    // The highlight slides between the two segments instead of jumping.
    val indicatorOffset by animateDpAsState(
        if (currentType == CaptureType.PHOTO) 0.dp else segmentWidth,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "captureTypeIndicator"
    )
    Surface(
        color = darkGray,
        shape = CircleShape,
        modifier = modifier
    ) {
        Box(modifier = Modifier.padding(2.dp)) {
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                .size(segmentWidth, 38.dp)
                .background(Color.White.copy(alpha = 0.2f), CircleShape)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(segmentWidth, 38.dp)
                    .clip(CircleShape)
                    .clickable { onTypeSelected(CaptureType.PHOTO) },
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
                    .size(segmentWidth, 38.dp)
                    .clip(CircleShape)
                    .clickable { onTypeSelected(CaptureType.VIDEO) },
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
}

/**
 * Zoom preset row. A single white highlight slides between the pills (in step with the
 * zoom glide when a preset is tapped, since it heads straight for [targetZoom]); the
 * highlighted pill shows the live zoom value, so it counts up/down during the glide and
 * shows e.g. "3.4x" while pinching between presets.
 */
@Composable
fun ZoomSwitcher(
    currentZoom: Float,
    zoomPresets: List<Float>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Preset being glided to (CameraUiState.zoomTarget), null when idle or pinching. */
    targetZoom: Float? = null,
    /** Zoom ratio -> label (0.7 on wide selfie cameras: full view reads ".7x"). */
    labelScale: Float = 1f,
) {
    val view = LocalView.current
    val pillSize = 42.dp
    // Idle/pinch: the largest preset at or below the current zoom. Tapped: the destination.
    val activePreset = zoomPresets.lastOrNull { it <= currentZoom + 0.05f } ?: zoomPresets.firstOrNull()
    val highlighted = targetZoom?.let { t -> zoomPresets.minByOrNull { abs(kotlin.math.ln(it / t)) } } ?: activePreset
    val highlightIndex = zoomPresets.indexOf(highlighted).coerceAtLeast(0)
    val highlightX by animateDpAsState(
        pillSize * highlightIndex,
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "zoomHighlight"
    )
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            if (zoomPresets.isNotEmpty()) {
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(highlightX.roundToPx(), 0) }
                        .size(pillSize)
                        .background(Color.White, CircleShape)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                zoomPresets.forEach { zoom ->
                    val isSelected = zoom == highlighted
                    val labelColor by animateColorAsState(
                        if (isSelected) Color.Black else Color.White, tween(160), label = "zoomLabel"
                    )
                    // Visual text size only; must not shadow the ratio->label [labelScale] parameter.
                    val textScale by animateFloatAsState(
                        if (isSelected) 1f else 0.9f,
                        spring(stiffness = Spring.StiffnessMediumLow), label = "zoomTextScale"
                    )
                    Box(
                        modifier = Modifier
                            .size(pillSize)
                            .clip(CircleShape)
                            .clickable {
                                if (zoom != highlighted || targetZoom != null || abs(currentZoom - zoom) > 0.01f) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                onZoomSelected(zoom)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatZoom((if (isSelected) currentZoom else zoom) * labelScale),
                            color = labelColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer {
                                scaleX = textScale
                                scaleY = textScale
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Space between the zoom row and the viewfinder's bottom edge (or the shutter controls). */
private val ZoomControlsGap = 16.dp

/**
 * Zoom presets (plus Portrait's blur control above them) floating with their bottom edge at
 * [anchorBottom], a y position in the parent in px (NaN until measured: hidden). Place it at
 * the parent's top. Fades out and ignores taps while not [visible].
 */
@Composable
fun ViewfinderZoomControls(
    anchorBottom: () -> Float,
    /**
     * Live zoom ratio. A provider so per-frame zoom changes recompose only this row (it is
     * read here, not by the caller).
     */
    zoomLevel: () -> Float,
    zoomPresets: List<Float>,
    onZoomChanged: (Float) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** Preset the zoom is gliding to, if any (moves the highlight straight there). */
    zoomTarget: () -> Float? = { null },
    /** Portrait mode's simulated aperture; null hides the blur control. */
    portraitAperture: Float? = null,
    onPortraitApertureChange: (Float) -> Unit = {},
    /** CameraUiState.zoomLabelScale. */
    zoomLabelScale: Float = 1f,
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "zoomChrome")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val bottom = anchorBottom()
                if (bottom.isNaN()) return@layout layout(0, 0) {}
                // Report the full height down to the anchor (the empty part takes no touches),
                // so the buttons stay inside this node's bounds for hit testing.
                val height = bottom.roundToInt().coerceAtLeast(placeable.height)
                layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
            }
            .graphicsLayer { this.alpha = alpha }
    ) {
        if (portraitAperture != null) {
            com.fearmikey.camera.ui.camera.PortraitApertureControl(
                aperture = portraitAperture,
                onApertureChange = { if (visible) onPortraitApertureChange(it) },
            )
        }
        ZoomSwitcher(
            currentZoom = zoomLevel(),
            zoomPresets = zoomPresets,
            onZoomSelected = { if (visible) onZoomChanged(it) },
            targetZoom = zoomTarget(),
            labelScale = zoomLabelScale,
        )
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
    lastCapturedUri: android.net.Uri?,
    orientation: Int,
    onModeSelected: (CameraMode) -> Unit,
    onCapture: () -> Unit,
    /** Auto Night will take the next shot: moon on the shutter. */
    nightShutter: Boolean = false,
    /** A multi-frame capture is running: the ring shows [captureProgress]. */
    showCaptureProgress: Boolean = false,
    /** Holding the shutter records (QuickTake / hold-to-record); otherwise a hold is a tap. */
    supportsQuickTake: Boolean = true,
    /** The right button takes a still while recording (CameraX video only). */
    supportsVideoSnapshot: Boolean = true,
    /** Manual controls work in this mode; the Tune button is dimmed otherwise. */
    manualControlsAvailable: Boolean = true,
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
    // While recording, modes, photo/video and settings are locked (they would rebind the camera).
    val canSwitch = idle && !isRecording
    val switchAlpha by animateFloatAsState(if (canSwitch) 1f else if (idle) 0.38f else 0f, label = "switchChrome")
    // The fades are read in the draw phase (graphicsLayer), so they don't recompose the
    // controls on every animation frame.
    val chromeFade = Modifier.graphicsLayer { alpha = chromeAlpha }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    .then(chromeFade)
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
                androidx.compose.animation.Crossfade(isRecording, animationSpec = tween(200), label = "leftButton") { recording ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (recording) {
                            androidx.compose.animation.Crossfade(isPaused, animationSpec = tween(150), label = "pauseIcon") { paused ->
                                Icon(
                                    imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (paused) "Resume" else "Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
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
                }
            }

            // Shutter: fixed footprint in every state (the recording time lives at the top of
            // the viewfinder, so nothing here changes size while recording).
            val isPanorama = currentMode == CameraMode.PANORAMA
            Box(contentAlignment = Alignment.Center) {
                com.fearmikey.camera.ui.camera.ShutterButton(
                    style = when {
                        isPanorama -> com.fearmikey.camera.ui.camera.ShutterStyle.PANORAMA
                        CameraModes.isVideo(currentMode) -> com.fearmikey.camera.ui.camera.ShutterStyle.VIDEO
                        else -> com.fearmikey.camera.ui.camera.ShutterStyle.PHOTO
                    },
                    isRecording = isRecording,
                    isPanoramaCapturing = isPanoramaCapturing,
                    progress = captureProgress.takeIf { showCaptureProgress },
                    onCapture = onCapture,
                    // Hold = QuickTake in Photo/Portrait, hold-to-record in Video; elsewhere a tap.
                    onLongPressStart = if (supportsQuickTake) onQuickTakeStart else null,
                    onLongPressEnd = onQuickTakeStop,
                )
                // No pointer input, so taps still reach the shutter underneath.
                androidx.compose.animation.AnimatedVisibility(
                    visible = nightShutter,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    RotatableIcon(
                        imageVector = Icons.Default.NightsStay,
                        contentDescription = "Night shot",
                        targetRotation = orientation,
                        tint = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Right Button: Flip Camera or Video Snapshot (no button while recording in modes
            // without snapshots, e.g. slow motion and time lapse).
            val snapshotHidden = isRecording && !supportsVideoSnapshot
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { alpha = if (snapshotHidden) 0f else chromeAlpha }
                    .clip(CircleShape)
                    .background(darkGray)
                    .clickable(enabled = idle && !snapshotHidden) { if (isRecording) onVideoSnapshot() else onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.Crossfade(isRecording, animationSpec = tween(200), label = "rightButton") { recording ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (recording) {
                            // Snapshot during video: a miniature photo shutter (ring + white disc).
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .border(2.5.dp, Color.White, CircleShape)
                                    .padding(5.dp)
                                    .background(Color.White, CircleShape)
                            )
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
            }
        }

        // Modes below shutter
        CameraModeSelector(
            modes = currentModes,
            currentMode = currentMode,
            onModeSelected = { if (canSwitch) onModeSelected(it) },
            enabled = canSwitch,
            modifier = Modifier.padding(bottom = 12.dp).graphicsLayer { alpha = switchAlpha }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
                .then(chromeFade),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.fearmikey.camera.ui.camera.RotatingIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                rotation = orientation,
                onClick = { if (canSwitch) onSettingsClick() },
                modifier = Modifier.alpha(if (isRecording) 0.38f else 1f),
            )

            PhotoVideoToggle(
                currentType = captureType,
                onTypeSelected = { if (canSwitch) onTypeSelected(it) },
                modifier = Modifier.alpha(if (isRecording) 0.38f else 1f),
            )

            // Dimmed (still tappable, the ViewModel explains why) where manual controls are ignored.
            com.fearmikey.camera.ui.camera.RotatingIconButton(
                icon = Icons.Default.Tune,
                contentDescription = if (manualControlsAvailable) "Manual controls" else "Manual controls (not available in this mode)",
                rotation = orientation,
                onClick = { if (idle) onProControlsClick() },
                tint = if (isProMode) com.fearmikey.camera.ui.camera.OnCameraAccent else Color.White,
                background = if (isProMode) CameraAccent else darkGray,
                modifier = Modifier.alpha(if (manualControlsAvailable || isProMode) 1f else 0.38f),
            )
        }
    }
}
