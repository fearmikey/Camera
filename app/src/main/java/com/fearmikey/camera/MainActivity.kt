package com.fearmikey.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fearmikey.camera.domain.model.CameraMode
import com.fearmikey.camera.ui.camera.CameraViewModel
import com.fearmikey.camera.data.engine.CameraXProvider
import com.fearmikey.camera.data.engine.Camera2Provider
import com.fearmikey.camera.data.engine.HybridCaptureEngine
import com.fearmikey.camera.data.engine.processing.ImageProcessor
import com.fearmikey.camera.data.engine.processing.PortraitSegmenter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val processor = ImageProcessor(PortraitSegmenter(this))
        val cameraXProvider = CameraXProvider(this, processor)
        val camera2Provider = Camera2Provider(this)
        val hybridCaptureEngine = HybridCaptureEngine(cameraXProvider, camera2Provider)
        val viewModel = CameraViewModel(hybridCaptureEngine)

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
                viewModel = viewModel
            )

            // UI Overlays
            CameraControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                currentMode = uiState.currentMode,
                onModeSelected = { viewModel.setMode(it) },
                onCapture = { viewModel.takePicture() }
            )
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
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                this.scaleType = scaleType
                viewModel.bindToLifecycle(lifecycleOwner, this.surfaceProvider)
            }
        }
    )
}

@Composable
fun CameraControls(
    modifier: Modifier = Modifier,
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    onCapture: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mode Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CameraMode.entries.forEach { mode ->
                Text(
                    text = mode.name,
                    color = if (mode == currentMode) Color.Yellow else Color.White,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable { onModeSelected(mode) },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Shutter Button
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color.White, CircleShape)
                .padding(4.dp)
                .background(Color.Black, CircleShape)
                .padding(4.dp)
                .background(Color.White, CircleShape)
                .clickable { onCapture() }
        )
    }
}
