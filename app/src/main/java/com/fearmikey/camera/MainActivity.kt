package com.fearmikey.camera

import android.Manifest
import android.content.Context
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
import com.fearmikey.camera.domain.model.AspectRatio as DomainAspectRatio
import com.fearmikey.camera.data.repository.MediaRepository
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.ui.settings.SettingsSheet
import kotlinx.coroutines.delay
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

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
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
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

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

            // UI Overlays
            if (uiState.isProMode) {
                ProControlOverlay(
                    uiState = uiState,
                    onConfigChange = { viewModel.updateProConfig(it) },
                    modifier = Modifier.align(Alignment.CenterEnd).width(250.dp).fillMaxHeight()
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
                    if (!uiState.isRecording && uiState.currentMode != CameraMode.VIDEO) {
                        showFlash = true
                    }
                },
                onQuickTakeStart = { viewModel.quickTakeStart() },
                onQuickTakeStop = { viewModel.quickTakeStop() },
                onProToggle = { viewModel.toggleProMode() },
                onThumbnailClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onSettingsClick = { viewModel.setShowSettingsSheet(true) }
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
                Box(modifier = Modifier.fillMaxSize().background(Color.White))
                LaunchedEffect(Unit) {
                    delay(50)
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
            }
    ) {
        val width = maxWidth
        val height = if (aspectRatio > 0) {
            width / aspectRatio
        } else {
            maxHeight
        }

        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .align(Alignment.Center)
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
        
        // Remove the old GridLinesOverlay call if it was outside
    }
}

// Remove old GridLinesOverlay Composable

@Composable
fun CameraControls(
    modifier: Modifier = Modifier,
    uiState: com.fearmikey.camera.ui.camera.CameraUiState,
    onModeSelected: (CameraMode) -> Unit,
    onCapture: () -> Unit,
    onQuickTakeStart: () -> Unit,
    onQuickTakeStop: () -> Unit,
    onProToggle: () -> Unit,
    onThumbnailClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Settings Button
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Pro Toggle
                Button(
                    onClick = onProToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isProMode) Color.Yellow else Color.DarkGray
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("PRO", color = if (uiState.isProMode) Color.Black else Color.White)
                }
            }

            // Mode Selector
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                CameraMode.entries.forEach { mode ->
                    Text(
                        text = mode.name,
                        color = if (mode == uiState.currentMode) Color.Yellow else Color.White,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable { onModeSelected(mode) },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            
            // Placeholder for balance
            Spacer(modifier = Modifier.width(64.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recent Media Thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray)
                    .clickable { onThumbnailClick() }
            ) {
                if (uiState.lastCapturedUri != null) {
                    AsyncImage(
                        model = uiState.lastCapturedUri,
                        contentDescription = "Last captured media",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Shutter Button with Quick Take and Progress
            val progress by animateFloatAsState(
                targetValue = if (uiState.isProcessing) 1f else 0f,
                animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
                label = "shutterProgress"
            )

            Box(
                modifier = Modifier.size(80.dp),
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
                val shutterShape = if (uiState.isRecording) androidx.compose.foundation.shape.RoundedCornerShape(12.dp) else CircleShape

                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .background(shutterColor, CircleShape)
                        .padding(4.dp)
                        .background(Color.Black, CircleShape)
                        .padding(4.dp)
                        .clip(shutterShape)
                        .background(shutterColor)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val result = withTimeoutOrNull(500) {
                                    waitForUpOrCancellation()
                                }
                                if (result == null) {
                                    // Long press
                                    onQuickTakeStart()
                                    waitForUpOrCancellation()
                                    onQuickTakeStop()
                                } else {
                                    // Tap
                                    onCapture()
                                }
                            }
                        }
                )
            }

            // Empty spacer for balance
            Spacer(modifier = Modifier.size(56.dp))
        }
    }
}
