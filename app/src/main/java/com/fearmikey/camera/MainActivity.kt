package com.fearmikey.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.FlashOn
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        val processor = ImageProcessor(PortraitSegmenter(this))
        val cameraXProvider = CameraXProvider(this, processor)
        val camera2Provider = Camera2Provider(this, processor)
        val hybridCaptureEngine = HybridCaptureEngine(cameraXProvider, camera2Provider)
        val mediaRepository = MediaRepository(this)
        val userPreferencesRepository = UserPreferencesRepository(this)
        val viewModel = CameraViewModel(hybridCaptureEngine, mediaRepository, userPreferencesRepository)

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
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    
    var showFlash by remember { mutableStateOf(false) }
    
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
                viewModel = viewModel,
                onModeSwiped = { direction ->
                    val modes = CameraMode.entries
                    val currentIndex = modes.indexOf(uiState.currentMode)
                    val nextIndex = if (direction > 0) {
                        (currentIndex - 1).coerceAtLeast(0)
                    } else {
                        (currentIndex + 1).coerceAtMost(modes.size - 1)
                    }
                    if (nextIndex != currentIndex) {
                        viewModel.setMode(modes[nextIndex])
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            )

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = systemBarsPadding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.setShowSettingsSheet(true) },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                ) {
                    RotatableIcon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        targetRotation = uiState.orientation,
                        tint = Color.White
                    )
                }
                
                Row {
                    IconButton(
                        onClick = { viewModel.toggleProMode() },
                        modifier = Modifier.background(
                            if (uiState.isProMode) Color.Yellow.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.3f),
                            CircleShape
                        )
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = uiState.orientation.toFloat(),
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "proTextRotation"
                        )
                        Text(
                            "PRO",
                            color = if (uiState.isProMode) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.rotate(rotation)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { /* Flash Toggle */ },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        RotatableIcon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "Flash",
                            targetRotation = uiState.orientation,
                            tint = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = { /* Timer Toggle */ },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        RotatableIcon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Timer",
                            targetRotation = uiState.orientation,
                            tint = Color.White
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
                uiState = uiState,
                onModeSelected = { viewModel.setMode(it) },
                onCapture = { 
                    viewModel.toggleCapture()
                    if (!uiState.isRecording && (uiState.currentMode != CameraMode.VIDEO)) {
                        showFlash = true
                    }
                },
                onQuickTakeStart = { viewModel.quickTakeStart() },
                onQuickTakeStop = { viewModel.quickTakeStop() },
                onThumbnailClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onZoomSelected = { viewModel.setZoom(it) },
                onFlipCamera = { viewModel.toggleCamera() }
            )

            if (uiState.showSettingsSheet) {
                SettingsSheet(
                    uiState = uiState,
                    onDismiss = { viewModel.setShowSettingsSheet(false) },
                    onAspectRatioChange = { viewModel.updateAspectRatio(it) },
                    onVideoResolutionChange = { viewModel.updateVideoResolution(it) },
                    onImageFormatChange = { viewModel.updateImageFormat(it) },
                    onVideoCodecChange = { viewModel.updateVideoCodec(it) },
                    onGridLinesToggle = { viewModel.toggleGridLines() },
                    onLevelerToggle = { viewModel.toggleLeveler() },
                    onLocationTaggingToggle = { viewModel.toggleLocationTagging() }
                )
            }

            // Flash Overlay
            if (showFlash) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                LaunchedEffect(Unit) {
                    delay(50.milliseconds)
                    showFlash = false
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
                            CameraMode.NIGHT -> "Processing Night Sight..."
                            else -> "Processing..."
                        }
                        Text(processingText, color = Color.White)
                    }
                }
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
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onModeSwiped: (Float) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        viewModel.setZoom(uiState.zoomLevel * zoomChange)
    }

    val aspectRatio = when (uiState.userPreferences.aspectRatio) {
        DomainAspectRatio.RATIO_4_3 -> 3f / 4f
        DomainAspectRatio.RATIO_16_9 -> 9f / 16f
        DomainAspectRatio.RATIO_FULL -> 0f // Handle separately
    }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .transformable(state = transformableState)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (abs(dragAmount) > 50) {
                        onModeSwiped(dragAmount)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val previewWidth: androidx.compose.ui.unit.Dp
        val previewHeight: androidx.compose.ui.unit.Dp

        if (aspectRatio > 0) {
            val constraintsRatio = maxWidth / maxHeight
            if (constraintsRatio > aspectRatio) {
                // Constraints are wider than aspect ratio
                previewHeight = maxHeight
                previewWidth = maxHeight * aspectRatio
            } else {
                // Constraints are taller than aspect ratio
                previewWidth = maxWidth
                previewHeight = maxWidth / aspectRatio
            }
        } else {
            previewWidth = maxWidth
            previewHeight = maxHeight
        }

        Box(
            modifier = Modifier
                .size(previewWidth, previewHeight)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        viewModel.focusAt(offset.x / size.width, offset.y / size.height)
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewView(context).apply {
                        this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        viewModel.bindToLifecycle(lifecycleOwner, this.surfaceProvider)
                    }
                }
            )
            
            HudOverlay(
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Remove old GridLinesOverlay Composable

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
    
    // Auto-scroll to current mode if needed
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
        items(modes) { mode ->
            val isSelected = mode == currentMode
            Text(
                text = mode.name,
                color = if (isSelected) Color.Yellow else Color.White,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { onModeSelected(mode) },
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            )
        }
    }
}

@Composable
fun ZoomSwitcher(
    currentZoom: Float,
    zoomPresets: List<Float>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            zoomPresets.forEach { zoom ->
                val isSelected = abs(currentZoom - zoom) < 0.05f
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { onZoomSelected(zoom) },
                    contentAlignment = Alignment.Center
                ) {
                    val label = when {
                        zoom < 1f -> ".${(zoom * 10).toInt()}"
                        zoom == zoom.toInt().toFloat() -> "${zoom.toInt()}x"
                        else -> "${zoom}x"
                    }
                    Text(
                        text = label,
                        color = if (isSelected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall,
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
    uiState: com.fearmikey.camera.ui.camera.CameraUiState,
    onModeSelected: (CameraMode) -> Unit,
    onCapture: () -> Unit,
    onQuickTakeStart: () -> Unit,
    onQuickTakeStop: () -> Unit,
    onThumbnailClick: () -> Unit,
    onZoomSelected: (Float) -> Unit,
    onFlipCamera: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Zoom Pill
        ZoomSwitcher(
            currentZoom = uiState.zoomLevel,
            zoomPresets = uiState.zoomPresets,
            onZoomSelected = onZoomSelected,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Mode Selector
        CameraModeSelector(
            modes = CameraMode.entries,
            currentMode = uiState.currentMode,
            onModeSelected = onModeSelected,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Main Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Thumbnail
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray.copy(alpha = 0.5f))
                    .clickable { onThumbnailClick() },
            ) {
                uiState.lastCapturedUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Last captured media",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // Shutter Button
            val progress by animateFloatAsState(
                targetValue = if (uiState.isProcessing) 1f else 0f,
                animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
                label = "shutterProgress"
            )

            Box(
                modifier = Modifier.size(86.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isProcessing && (uiState.currentMode == CameraMode.NIGHT || uiState.currentMode == CameraMode.HDR)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.Yellow.copy(alpha = 0.3f),
                            style = Stroke(width = 4.dp.toPx())
                        )
                        drawArc(
                            color = Color.Yellow,
                            startAngle = -90f,
                            sweepAngle = progress * 360f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx())
                        )
                    }
                }

                val isVideoMode = uiState.currentMode == CameraMode.VIDEO || uiState.currentMode == CameraMode.SLOW_MO
                val shutterColor = if (isVideoMode || uiState.isRecording) Color.Red else Color.White
                val shutterInnerShape = if (uiState.isRecording) androidx.compose.foundation.shape.RoundedCornerShape(8.dp) else CircleShape

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .padding(4.dp)
                        .background(Color.Black, CircleShape)
                        .padding(4.dp)
                        .clip(shutterInnerShape)
                        .background(shutterColor)
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
            }

            // Camera Flip Button
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray.copy(alpha = 0.5f))
                    .clickable { onFlipCamera() },
                contentAlignment = Alignment.Center
            ) {
                RotatableIcon(
                    imageVector = Icons.Default.Cached,
                    contentDescription = "Switch Camera",
                    targetRotation = uiState.orientation,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
