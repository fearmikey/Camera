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
import androidx.compose.animation.animateColorAsState
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
import coil.compose.AsyncImage
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.domain.model.FlashMode
import com.fearmikey.camera.domain.model.CaptureType
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.LightMode
import com.fearmikey.camera.domain.model.LensSelectionMode
import com.fearmikey.camera.SettingsActivity
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.ui.camera.CameraViewModel
import com.fearmikey.camera.ui.camera.ProControlOverlay
import com.fearmikey.camera.ui.camera.HudOverlay
import com.fearmikey.camera.data.engine.CameraXProvider
import com.fearmikey.camera.data.engine.Camera2Provider
import com.fearmikey.camera.data.engine.HybridCaptureEngine
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.processing.PortraitSegmenter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        if (intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA || 
            intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE ||
            intent.action == "android.media.action.IMAGE_CAPTURE_SECURE") {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        
        val processor = ImageProcessor(this, PortraitSegmenter(this))
        val cameraXProvider = CameraXProvider(this, processor)
        val camera2Provider = Camera2Provider(this, processor)
        val hybridCaptureEngine = HybridCaptureEngine(cameraXProvider, camera2Provider)
        val mediaRepository = MediaRepository(this)
        val userPreferencesRepository = UserPreferencesRepository(this)
        viewModel = CameraViewModel(hybridCaptureEngine, mediaRepository, userPreferencesRepository)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        viewModel.initSensors(sensorManager)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    CameraScreen(viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::viewModel.isInitialized && viewModel.uiState.value.userPreferences.volumeKeyShutter) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                viewModel.toggleCapture()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
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
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
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
                autoShowLeveler = uiState.autoShowLeveler,
                horizonTilt = uiState.horizonTilt,
                focusPoint = uiState.focusPoint,
                onZoomChanged = { viewModel.setZoom(it) },
                zoomPresets = uiState.zoomPresets,
                isManualLens = uiState.userPreferences.lensSelection == LensSelectionMode.MANUAL,
                onFocusAt = { x, y -> viewModel.focusAt(x, y) },
                onBindToLifecycle = { owner, provider -> viewModel.bindToLifecycle(owner, provider) },
                onModeSwiped = { direction ->
                    val photoModes = listOf(CameraMode.ADD_ME, CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.NIGHT_SIGHT, CameraMode.PANORAMA)
                    val videoModes = listOf(CameraMode.VIDEO, CameraMode.SLOW_MO)
                    val modes = if (uiState.captureType == CaptureType.PHOTO) photoModes else videoModes
                    
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

            // Top Bar Icons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = systemBarsPadding.calculateTopPadding() + 8.dp, start = 20.dp, end = 20.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Resolution indicator
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.2f))
                            .border(1.dp, Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable { 
                                val nextRes = if (uiState.userPreferences.resolution == ResolutionMode.MP_12) 
                                    ResolutionMode.MP_50 else ResolutionMode.MP_12
                                viewModel.updateResolution(nextRes)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (uiState.userPreferences.resolution == ResolutionMode.MP_12) "12" else "50",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp
                            )
                            Text(
                                "MP",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                fontWeight = FontWeight.Bold,
                                lineHeight = 8.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Macro Indicator
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocalFlorist,
                            contentDescription = "Macro",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            "A",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, fontWeight = FontWeight.Black),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Light Mode Indicator
                    if (uiState.userPreferences.lightMode != LightMode.NONE) {
                        val icon = when (uiState.userPreferences.lightMode) {
                            LightMode.AUTO_NIGHT_SIGHT -> Icons.Default.AutoMode
                            LightMode.FLASH -> Icons.Default.FlashOn
                            else -> Icons.Default.Block
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Light Mode",
                            tint = if (uiState.userPreferences.lightMode == LightMode.FLASH) Color.Yellow else Color.White,
                            modifier = Modifier.size(22.dp).clickable { viewModel.setShowSettingsSheet(true) }
                        )
                    }
                }

                // Top Right Flip Camera (Shortcut)
                IconButton(
                    onClick = { /* Flip shortcut */ },
                    modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip Camera",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // UI Overlays
            if (uiState.isProMode) {
                ProControlOverlay(
                    uiState = uiState,
                    onConfigChange = { viewModel.updateProConfig(it) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(250.dp)
                        .fillMaxHeight()
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
                captureProgress = uiState.captureProgress,
                recordingDuration = uiState.recordingDuration,
                lastCapturedUri = uiState.lastCapturedUri,
                orientation = uiState.orientation,
                onModeSelected = { viewModel.setMode(it) },
                onCapture = { 
                    viewModel.toggleCapture()
                    if (!uiState.isRecording && (uiState.currentMode != CameraMode.VIDEO)) {
                        showFlashOverlay = true
                    }
                },
                onPause = { viewModel.pauseVideo() },
                onResume = { viewModel.resumeVideo() },
                onVideoSnapshot = { viewModel.takePhotoDuringVideo() },
                onQuickTakeStart = { viewModel.quickTakeStart() },
                onQuickTakeStop = { viewModel.quickTakeStop() },
                onThumbnailClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onFlipCamera = { viewModel.toggleCamera() },
                onTypeSelected = { viewModel.setCaptureType(it) },
                onSettingsClick = { viewModel.setShowSettingsSheet(true) }
            )

            if (uiState.showSettingsSheet) {
                SettingsSheet(
                    userPreferences = uiState.userPreferences,
                    captureType = uiState.captureType,
                    onDismiss = { viewModel.setShowSettingsSheet(false) },
                    onAspectRatioChange = { viewModel.updateAspectRatio(it) },
                    onTimerChange = { viewModel.updateTimer(it) },
                    onLightModeChange = { viewModel.updateLightMode(it) },
                    onMacroFocusChange = { viewModel.updateMacroFocus(it) },
                    onTopShotToggle = { viewModel.updateTopShot(it) },
                    onMotionPhotoChange = { viewModel.updateMotionPhoto(it) },
                    onResolutionChange = { viewModel.updateResolution(it) },
                    onSaveModeChange = { viewModel.updateSaveMode(it) },
                    onLensSelectionChange = { viewModel.updateLensSelection(it) },
                    onGridLinesToggle = { viewModel.toggleGridLines() },
                    onLevelerToggle = { viewModel.toggleLeveler() },
                    onLocationTaggingToggle = { viewModel.toggleLocationTagging() },
                    onVolumeKeyShutterToggle = { viewModel.toggleVolumeKeyShutter() },
                    onGestureTriggerToggle = { viewModel.toggleGestureTrigger() },
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

            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Yellow)
                        Spacer(modifier = Modifier.height(8.dp))
                        val processingText = when (uiState.currentMode) {
                            CameraMode.HDR -> "HDR Processing..."
                            CameraMode.NIGHT_SIGHT -> "Processing Night Sight..."
                            else -> "Processing..."
                        }
                        Text(processingText, color = Color.White)
                    }
                }
            }

            uiState.timerCountdown?.let { countdown ->
                TimerCountdownOverlay(countdown = countdown)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required", color = Color.White)
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: DomainAspectRatio,
    zoomLevel: Float,
    zoomPresets: List<Float>,
    isManualLens: Boolean,
    isFrontCamera: Boolean,
    gridMode: com.fearmikey.camera.domain.model.GridMode,
    showLeveler: Boolean,
    autoShowLeveler: Boolean,
    horizonTilt: Float,
    focusPoint: androidx.compose.ui.geometry.Offset?,
    onZoomChanged: (Float) -> Unit,
    onFocusAt: (Float, Float) -> Unit,
    onBindToLifecycle: (androidx.lifecycle.LifecycleOwner, androidx.camera.core.Preview.SurfaceProvider) -> Unit,
    onModeSwiped: (Float) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var currentScale by remember(zoomLevel) { mutableFloatStateOf(zoomLevel) }
    var totalDragX by remember { mutableFloatStateOf(0f) }

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
                        currentScale *= zoom
                        onZoomChanged(currentScale)
                        totalDragX = 0f
                    } else {
                        totalDragX += pan.x
                        if (abs(totalDragX) > 200) {
                            onModeSwiped(totalDragX)
                            totalDragX = 0f
                        }
                    }
                }
            },
        contentAlignment = BiasAlignment(0f, -0.7f) // Adjusted bias for better spacing
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

        Box(
            modifier = Modifier
                .size(previewWidth, previewHeight)
                .clip(RoundedCornerShape(32.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onFocusAt(offset.x / size.width, offset.y / size.height)
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        onBindToLifecycle(lifecycleOwner, this.surfaceProvider)
                    }
                },
                update = {
                    // No need to re-bind if nothing changed that affects the binder
                    // CameraX handles surface updates internally
                }
            )
            
            HudOverlay(
                gridMode = gridMode,
                showLeveler = showLeveler,
                autoShowLeveler = autoShowLeveler,
                horizonTilt = horizonTilt,
                focusPoint = focusPoint,
                modifier = Modifier.fillMaxSize()
            )

            // Zoom pill inside preview, matching Google Camera
            ZoomSwitcher(
                currentZoom = zoomLevel,
                zoomPresets = zoomPresets,
                onZoomSelected = onZoomChanged,
                isManualLens = isManualLens,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
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
    val listState = rememberLazyListState()
    
    LaunchedEffect(currentMode) {
        val index = modes.indexOf(currentMode)
        if (index != -1) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = modes,
            key = { it.name }
        ) { mode ->
            val isSelected = mode == currentMode
            val label = when(mode) {
                CameraMode.PHOTO -> "Photo"
                CameraMode.VIDEO -> "Video"
                CameraMode.NIGHT_SIGHT -> "Night Sight"
                CameraMode.SLOW_MO -> "Slow Motion"
                CameraMode.PORTRAIT -> "Portrait"
                CameraMode.PANORAMA -> "Panorama"
                CameraMode.ADD_ME -> "Add Me"
                CameraMode.HDR -> "HDR"
            }
            
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

@Composable
fun PhotoVideoToggle(
    currentType: CaptureType,
    onTypeSelected: (CaptureType) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkGray = Color(0xFF202124)
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
    isManualLens: Boolean = false
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
            val displayPresets = if (isManualLens) listOf(0.6f, 1.0f, 2.0f) else listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)
            displayPresets.forEach { zoom ->
                val isSelected = abs(currentZoom - zoom) < 0.05f
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { onZoomSelected(zoom) },
                    contentAlignment = Alignment.Center
                ) {
                    val label = when {
                        isManualLens && zoom == 0.6f -> "UW"
                        isManualLens && zoom == 1.0f -> "W"
                        isManualLens && zoom == 2.0f -> "T"
                        zoom == 0.5f || zoom == 0.6f -> ".5"
                        zoom == 1.0f -> "1x"
                        else -> "${zoom.toInt()}"
                    }
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
    captureProgress: Float,
    recordingDuration: String,
    lastCapturedUri: android.net.Uri?,
    orientation: Int,
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
    onSettingsClick: () -> Unit
) {
    val photoModes = listOf(CameraMode.ADD_ME, CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.NIGHT_SIGHT, CameraMode.PANORAMA)
    val videoModes = listOf(CameraMode.VIDEO, CameraMode.SLOW_MO)
    val currentModes = if (captureType == CaptureType.PHOTO) photoModes else videoModes
    val darkGray = Color(0xFF202124)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ZoomSwitcher is now back in CameraPreview

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Button: Thumbnail or Pause/Resume
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(darkGray)
                    .clickable { 
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
                        AsyncImage(
                            model = uri,
                            contentDescription = "Last captured media",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
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
                    if (isProcessing && (currentMode == CameraMode.NIGHT_SIGHT || currentMode == CameraMode.HDR)) {
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

                    val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.SLOW_MO
                    val shutterColor = if (isVideoMode || isRecording) Color.Red else Color.White
                    val shutterInnerShape = if (isRecording) androidx.compose.foundation.shape.RoundedCornerShape(12.dp) else CircleShape

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(shutterInnerShape)
                            .background(shutterColor)
                            .border(2.dp, Color.Black.copy(alpha = 0.1f), shutterInnerShape)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    val result = withTimeoutOrNull(500) {
                                        waitForUpOrCancellation()
                                    }
                                    if (result == null) {
                                        onQuickTakeStart()
                                        waitForUpOrCancellation()
                                        onQuickTakeStop()
                                    } else {
                                        onCapture()
                                    }
                                }
                            }
                    )
                    
                    // Outer white ring for non-recording photo mode
                    if (!isRecording && !isVideoMode) {
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
                    .clip(CircleShape)
                    .background(darkGray)
                    .clickable { if (isRecording) onVideoSnapshot() else onFlipCamera() },
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
            onModeSelected = onModeSelected,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.background(darkGray, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            PhotoVideoToggle(
                currentType = captureType,
                onTypeSelected = onTypeSelected
            )

            IconButton(
                onClick = { /* Tuning/Filters */ },
                modifier = Modifier.background(darkGray, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Controls",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
