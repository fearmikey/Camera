package com.fearmikey.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.GridMode
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        val repository = UserPreferencesRepository(this)
        
        setContent {
            val prefs by repository.userPreferencesFlow.collectAsState(initial = UserPreferences())
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = Color.Black,
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White,
                            ),
                        )
                    },
                    containerColor = Color.Black,
                ) { padding ->
                    val coroutineScope = rememberCoroutineScope()
                    SettingsList(
                        prefs = prefs,
                        onToggle = { key, value ->
                            coroutineScope.launch {
                                when (key) {
                                    "location" -> repository.updateLocationTagging(value)
                                    "leveler" -> repository.updateShowLeveler(value)
                                    "camera_sounds" -> repository.updateCameraSounds(value)
                                    "mirror_selfies" -> repository.updateMirrorSelfies(value)
                                    "ultra_hdr" -> repository.updateUltraHdr(value)
                                    "timer_light" -> repository.updateTimerLight(value)
                                    "palm_timer" -> repository.updateGestureTrigger(value)
                                }
                            }
                        },
                        onGridModeChange = { mode ->
                            coroutineScope.launch { repository.updateGridMode(mode) }
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsList(
    prefs: UserPreferences,
    onToggle: (String, Boolean) -> Unit,
    onGridModeChange: (GridMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGridDialog by remember { mutableStateOf(value = false) }

    if (showGridDialog) {
        GridModeDialog(
            currentMode = prefs.gridMode,
            onModeSelected = {
                onGridModeChange(it)
                showGridDialog = false
            },
        ) { showGridDialog = false }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { SettingsHeader("General") }
        item { SettingsItem("Save location", "Other apps may be able to view photo & video location info", prefs.locationTagging) { onToggle("location", it) } }
        item { SettingsItem("Camera sounds", null, prefs.cameraSounds) { onToggle("camera_sounds", it) } }
        item { SettingsItem("Device storage", null, false, enabled = false) { } }
        item { SettingsItem("Camera scan suggestions", "Point your camera to scan QR codes and documents", true, enabled = false) { } }
        item { SettingsItem("Model download", null, false, enabled = false) { } }

        item { SettingsHeader("Composition") }
        item { SettingsItem("Framing hints", "On-screen tips and tools, like levelers and focus tips", prefs.showLeveler) { onToggle("leveler", it) } }
        item { 
            SettingsItem(
                title = "Grid type", 
                description = prefs.gridMode.label,
                onClick = { showGridDialog = true }
            ) 
        }
        item { SettingsItem("Keep selfies as seen", "Save selfies as they appear on screen", prefs.mirrorSelfies) { onToggle("mirror_selfies", it) } }
        item { SettingsItem("Guided Frame", "For people with blindness or low vision. Helps frame and automatically take photos.", false, enabled = false) { } }
        item { SettingsItem("Camera Coach (Preview)", "Get photo inspiration and capture the perfect shot with step-by-step guidance", false, enabled = false) { } }

        item { SettingsHeader("Fast access") }
        item { SettingsItem("Quick access controls", "Adjust white balance, exposure, and shadows by tapping the viewfinder", true, enabled = false) { } }
        item { SettingsItem("Volume key action", "Shutter", null, enabled = false) { } }
        item { SettingsItem("Palm timer", "Start a photo timer by showing your palm to the front or rear cameras", prefs.gestureTrigger) { onToggle("palm_timer", it) } }

        item { SettingsHeader("Photo") }
        item { SettingsItem("Rich color in photos", "Use Display P3 color format instead of sRGB for richer and more vibrant colors.", false, enabled = false) { } }
        item { SettingsItem("Ultra HDR", "Ultra HDR brightens the lightest parts of images...", prefs.ultraHdr, enabled = false) { onToggle("ultra_hdr", it) } }
        item { SettingsItem("Auto Best Take", "Automatically choose the best expressions...", true, enabled = false) { } }
        item { SettingsItem("Social media depth features", "Depth data will be saved...", false, enabled = false) { } }
        item { SettingsItem("Frequent Faces", null, false, enabled = false) { } }
        item { SettingsItem("Astrophotography", null, false, enabled = false) }
        
        item { SettingsHeader("Video") }
        item { SettingsItem("Video stabilization", "Reduce camera shake to make videos smoother.", true, enabled = false) { } }
        item { SettingsItem("Audio zoom", "Zooming in on subject boosts sound and reduces background noise", true, enabled = false) { } }
        item { SettingsItem("Video format", "H.265/HEVC", null, enabled = false) { } }
        item { SettingsItem("Optimize FPS", "Automatically adjust from 60 FPS to 30 FPS...", true, enabled = false) }
        
        item { SettingsHeader("Advanced capture") }
        item { SettingsItem("Timer light", "Flashes the back camera light during the timer countdown.", prefs.timerLight) { onToggle("timer_light", it) } }
        item { SettingsItem("Show dirty lens warning", "Display warning when the lens may be too dirty...", true, enabled = false) { } }
        item { SettingsItem("Underwater photography and video", "Restores true colors...", false, enabled = false) { } }
        
        item { SettingsHeader("Help & feedback") }
        item { SettingsItem("Send feedback", null, false, enabled = false) }
        
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun GridModeDialog(
    currentMode: GridMode,
    onModeSelected: (GridMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid type") },
        text = {
            Column {
                GridMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onModeSelected(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Color(0xFF2C2C2C),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFDDE4D7),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    description: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = (enabled && onClick != null)) { onClick?.invoke() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            description?.let {
                Text(
                    text = it,
                    color = Color.Gray.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }
        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
