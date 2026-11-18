package com.fearmikey.camera

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.domain.model.GridMode
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.VideoFrameRate
import com.fearmikey.camera.domain.model.VideoResolution
import com.fearmikey.camera.domain.model.VolumeKeyAction
import kotlinx.coroutines.launch

import com.fearmikey.camera.ui.theme.CameraAppTheme

/** Every action the settings list can trigger; keeps the list composable previewable. */
data class SettingsActions(
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onGridMode: (GridMode) -> Unit = {},
    val onVolumeKeyAction: (VolumeKeyAction) -> Unit = {},
    val onVideoResolution: (VideoResolution) -> Unit = {},
    val onVideoFrameRate: (VideoFrameRate) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = UserPreferencesRepository(this)

        setContent {
            val prefs by repository.userPreferencesFlow.collectAsState(initial = UserPreferences())
            val scope = rememberCoroutineScope()
            val actions = remember {
                SettingsActions(
                    onToggle = { key, value ->
                        scope.launch {
                            when (key) {
                                "location" -> repository.updateLocationTagging(value)
                                "leveler" -> repository.updateShowLeveler(value)
                                "camera_sounds" -> repository.updateCameraSounds(value)
                                "mirror_selfies" -> repository.updateMirrorSelfies(value)
                                "timer_light" -> repository.updateTimerLight(value)
                                "palm_timer" -> repository.updateGestureTrigger(value)
                                "scan_qr" -> repository.updateScanQrCodes(value)
                                "double_tap" -> repository.updateDoubleTapSwitch(value)
                                "low_light" -> repository.updateLowLightSuggestion(value)
                                "hdr" -> repository.updateHdrPhoto(value)
                                "stabilization" -> repository.updateVideoStabilization(value)
                                "audio" -> repository.updateRecordAudio(value)
                            }
                        }
                    },
                    onGridMode = { scope.launch { repository.updateGridMode(it) } },
                    onVolumeKeyAction = { scope.launch { repository.updateVolumeKeyAction(it) } },
                    onVideoResolution = { scope.launch { repository.updateVideoResolution(it) } },
                    onVideoFrameRate = { scope.launch { repository.updateVideoFrameRate(it) } },
                )
            }

            CameraAppTheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Camera settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                        )
                    },
                ) { padding ->
                    SettingsList(prefs = prefs, actions = actions, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
fun SettingsList(
    prefs: UserPreferences,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val freeSpace = remember {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            Formatter.formatShortFileSize(context, stat.availableBytes) + " available"
        }.getOrDefault("Unknown")
    }

    when (dialog) {
        "grid" -> ChoiceDialog("Grid type", GridMode.entries, prefs.gridMode, { it.label }, { actions.onGridMode(it); dialog = null }) { dialog = null }
        "volume" -> ChoiceDialog("Volume key action", VolumeKeyAction.entries, prefs.volumeKeyAction, { it.label }, { actions.onVolumeKeyAction(it); dialog = null }) { dialog = null }
        "resolution" -> ChoiceDialog("Video resolution", VideoResolution.entries, prefs.videoResolution, { it.value }, { actions.onVideoResolution(it); dialog = null }) { dialog = null }
        "fps" -> ChoiceDialog("Video frame rate", VideoFrameRate.entries, prefs.videoFrameRate, { it.label }, { actions.onVideoFrameRate(it); dialog = null }) { dialog = null }
    }

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    val toggle = actions.onToggle
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SettingsHeader("General") }
        item { SettingsItem("Save location", "Add GPS coordinates to photos and videos. Other apps may be able to view this information.", prefs.locationTagging, onCheckedChange = { toggle("location", it) }) }
        item { SettingsItem("Camera sounds", "Shutter, timer and recording sounds", prefs.cameraSounds, onCheckedChange = { toggle("camera_sounds", it) }) }
        item { SettingsItem("Device storage", "$freeSpace · Saved to DCIM/Camera") }
        item { SettingsItem("Scan QR codes", "Show a suggestion when a QR code is in view (Photo mode). Decoded on-device.", prefs.scanQrCodes, onCheckedChange = { toggle("scan_qr", it) }) }

        item { SettingsHeader("Composition") }
        item { SettingsItem("Framing hints", "Show a horizon leveler in the viewfinder", prefs.showLeveler, onCheckedChange = { toggle("leveler", it) }) }
        item { SettingsItem("Grid type", prefs.gridMode.label, onClick = { dialog = "grid" }) }
        item { SettingsItem("Keep selfies as seen", "Save selfies mirrored, as they appear on screen", prefs.mirrorSelfies, onCheckedChange = { toggle("mirror_selfies", it) }) }

        item { SettingsHeader("Fast access") }
        item { SettingsItem("Volume key action", prefs.volumeKeyAction.label, onClick = { dialog = "volume" }) }
        item { SettingsItem("Double-tap to switch camera", "Double-tap the viewfinder to flip between front and back", prefs.doubleTapSwitch, onCheckedChange = { toggle("double_tap", it) }) }
        item { SettingsItem("Palm timer", "Hold up an open hand next to your face to start a 3-second timer", prefs.gestureTrigger, onCheckedChange = { toggle("palm_timer", it) }) }

        item { SettingsHeader("Photo") }
        item { SettingsItem("HDR", "Capture an exposure bracket and merge it for more detail in highlights and shadows (slower)", prefs.hdrPhoto, onCheckedChange = { toggle("hdr", it) }) }
        item { SettingsItem("Low-light suggestions", "Offer Night mode when the scene is dark", prefs.lowLightSuggestion, onCheckedChange = { toggle("low_light", it) }) }

        item { SettingsHeader("Video") }
        item { SettingsItem("Resolution", prefs.videoResolution.value, onClick = { dialog = "resolution" }) }
        item { SettingsItem("Frame rate", prefs.videoFrameRate.label, onClick = { dialog = "fps" }) }
        item { SettingsItem("Video stabilization", "Reduce camera shake for smoother videos", prefs.videoStabilization, onCheckedChange = { toggle("stabilization", it) }) }
        item { SettingsItem("Record audio", null, prefs.recordAudio, onCheckedChange = { toggle("audio", it) }) }

        item { SettingsHeader("Advanced") }
        item { SettingsItem("Timer light", "Flash the rear light during the timer countdown", prefs.timerLight, onCheckedChange = { toggle("timer_light", it) }) }

        item { SettingsHeader("About") }
        item { SettingsItem("Version", versionName) }
        item {
            SettingsItem(
                "Free & open source",
                "No ads, no analytics, no network access. All processing happens on your device using OpenCV and the Android camera APIs."
            )
        }
        item { SettingsItem("License", "GNU General Public License v3.0 or later. Uses OpenCV and AndroidX (Apache-2.0).") }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
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
    val rowClick: (() -> Unit)? = when {
        !enabled -> null
        onClick != null -> onClick
        checked != null && onCheckedChange != null -> { { onCheckedChange(!checked) } }
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = rowClick != null) { rowClick?.invoke() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            description?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }
        if (checked != null) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Preview(name = "Light", widthDp = 393, heightDp = 900, showBackground = true)
@Preview(name = "Dark", widthDp = 393, heightDp = 900, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsListPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SettingsList(prefs = UserPreferences(), actions = SettingsActions())
        }
    }
}
