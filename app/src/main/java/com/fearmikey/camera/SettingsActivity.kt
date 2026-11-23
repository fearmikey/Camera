package com.fearmikey.camera

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.fearmikey.camera.data.repository.UserPreferencesRepository
import com.fearmikey.camera.domain.model.GridMode
import com.fearmikey.camera.domain.model.ImageSaveMode
import com.fearmikey.camera.domain.model.ResolutionMode
import com.fearmikey.camera.domain.model.UserPreferences
import com.fearmikey.camera.domain.model.VideoFrameRate
import com.fearmikey.camera.domain.model.VideoResolution
import com.fearmikey.camera.domain.model.VolumeKeyAction
import kotlinx.coroutines.launch

import com.fearmikey.camera.ui.theme.CameraAppTheme

/** External links shown in Settings → About / Support. Opened in the user's browser; the app itself has no network access. */
object AppLinks {
    const val SOURCE_CODE = "https://github.com/fearmikey/Camera"
    const val ISSUES = "$SOURCE_CODE/issues"
    const val CHANGELOG = "$SOURCE_CODE/blob/master/CHANGELOG.md"
    const val LICENSE = "$SOURCE_CODE/blob/master/LICENSE"
    const val APACHE_LICENSE = "https://www.apache.org/licenses/LICENSE-2.0"
    const val DONATE = "https://buymeacoffee.com/ximw7nxi1j"
}

/** On/off settings in [SettingsList]; [SettingsActions.onToggle] maps each to its repository update. */
enum class SettingToggle {
    LOCATION, LEVELER, CAMERA_SOUNDS, MIRROR_SELFIES, TIMER_LIGHT, PALM_TIMER, SCAN_CODES,
    DOUBLE_TAP, LOW_LIGHT, HDR, PORTRAIT_ORIGINAL, STABILIZATION, AUDIO,
}

/** Dialogs in [SettingsList]: choice dialogs plus the About section's license dialogs. */
private enum class SettingsDialog {
    GRID, VOLUME, PHOTO_RESOLUTION, FILE_FORMAT, VIDEO_RESOLUTION, FRAME_RATE, LICENSE, OPEN_SOURCE_LICENSES,
}

/** Runtime dependencies shown in the "Open-source licenses" dialog (keep in sync with README.md). */
private val ThirdPartyLicenses = listOf(
    "AndroidX (Compose, CameraX, DataStore, Lifecycle)" to "Apache License 2.0",
    "OpenCV 4.12" to "Apache License 2.0",
    "Kotlin standard library & coroutines" to "Apache License 2.0",
)

/** Every action the settings list can trigger; keeps the list composable previewable. */
data class SettingsActions(
    val onToggle: (SettingToggle, Boolean) -> Unit = { _, _ -> },
    val onGridMode: (GridMode) -> Unit = {},
    val onVolumeKeyAction: (VolumeKeyAction) -> Unit = {},
    val onPhotoResolution: (ResolutionMode) -> Unit = {},
    val onSaveMode: (ImageSaveMode) -> Unit = {},
    val onVideoResolution: (VideoResolution) -> Unit = {},
    val onVideoFrameRate: (VideoFrameRate) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
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
                                SettingToggle.LOCATION -> repository.updateLocationTagging(value)
                                SettingToggle.LEVELER -> repository.updateShowLeveler(value)
                                SettingToggle.CAMERA_SOUNDS -> repository.updateCameraSounds(value)
                                SettingToggle.MIRROR_SELFIES -> repository.updateMirrorSelfies(value)
                                SettingToggle.TIMER_LIGHT -> repository.updateTimerLight(value)
                                SettingToggle.PALM_TIMER -> repository.updateGestureTrigger(value)
                                SettingToggle.SCAN_CODES -> repository.updateScanQrCodes(value)
                                SettingToggle.DOUBLE_TAP -> repository.updateDoubleTapSwitch(value)
                                SettingToggle.LOW_LIGHT -> repository.updateLowLightSuggestion(value)
                                SettingToggle.HDR -> repository.updateHdrPhoto(value)
                                SettingToggle.PORTRAIT_ORIGINAL -> repository.updatePortraitKeepOriginal(value)
                                SettingToggle.STABILIZATION -> repository.updateVideoStabilization(value)
                                SettingToggle.AUDIO -> repository.updateRecordAudio(value)
                            }
                        }
                    },
                    onGridMode = { scope.launch { repository.updateGridMode(it) } },
                    onVolumeKeyAction = { scope.launch { repository.updateVolumeKeyAction(it) } },
                    onPhotoResolution = { scope.launch { repository.updateResolution(it) } },
                    onSaveMode = { scope.launch { repository.updateSaveMode(it) } },
                    onVideoResolution = { scope.launch { repository.updateVideoResolution(it) } },
                    onVideoFrameRate = { scope.launch { repository.updateVideoFrameRate(it) } },
                    onOpenUrl = ::openUrl,
                )
            }

            CameraAppTheme {
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                val barColor = MaterialTheme.colorScheme.surfaceContainer
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = barColor,
                    topBar = {
                        LargeTopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            colors = TopAppBarDefaults.largeTopAppBarColors(
                                containerColor = barColor,
                                scrolledContainerColor = barColor,
                            ),
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { padding ->
                    SettingsList(prefs = prefs, actions = actions, contentPadding = padding)
                }
            }
        }
    }

    /** Opens [url] in the browser (the camera app itself never touches the network). */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open $url", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun SettingsList(
    prefs: UserPreferences,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val freeSpace = remember {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            Formatter.formatShortFileSize(context, stat.availableBytes) + " available"
        }.getOrDefault("Unknown")
    }

    val close = { dialog = null }
    when (dialog) {
        SettingsDialog.GRID -> ChoiceDialog("Grid", GridMode.entries, prefs.gridMode, { it.label }, { actions.onGridMode(it); close() }, close)
        SettingsDialog.VOLUME -> ChoiceDialog("Volume key action", VolumeKeyAction.entries, prefs.volumeKeyAction, { it.label }, { actions.onVolumeKeyAction(it); close() }, close)
        SettingsDialog.PHOTO_RESOLUTION -> ChoiceDialog("Photo resolution", ResolutionMode.entries, prefs.resolution, { it.label }, { actions.onPhotoResolution(it); close() }, close)
        SettingsDialog.FILE_FORMAT -> ChoiceDialog("File format", ImageSaveMode.entries, prefs.saveMode, { it.label }, { actions.onSaveMode(it); close() }, close)
        SettingsDialog.VIDEO_RESOLUTION -> ChoiceDialog("Video resolution", VideoResolution.entries, prefs.videoResolution, { it.value }, { actions.onVideoResolution(it); close() }, close)
        SettingsDialog.FRAME_RATE -> ChoiceDialog("Frame rate", VideoFrameRate.entries, prefs.videoFrameRate, { it.label }, { actions.onVideoFrameRate(it); close() }, close)
        SettingsDialog.LICENSE -> LicenseDialog(onViewFull = { actions.onOpenUrl(AppLinks.LICENSE); close() }, onDismiss = close)
        SettingsDialog.OPEN_SOURCE_LICENSES -> OpenSourceLicensesDialog(onViewApache = { actions.onOpenUrl(AppLinks.APACHE_LICENSE); close() }, onDismiss = close)
        null -> Unit
    }

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    val toggle = actions.onToggle
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        // Setting names match the in-camera quick-settings sheet (SettingsSheet.kt).
        item {
            SettingsGroup("General") {
                SettingsItem(Icons.Outlined.LocationOn, "Save location", "Add GPS coordinates to photos and videos. Other apps may be able to view this information.", prefs.locationTagging, onCheckedChange = { toggle(SettingToggle.LOCATION, it) })
                SettingsItem(Icons.Outlined.MusicNote, "Camera sounds", "Shutter, timer and recording sounds", prefs.cameraSounds, onCheckedChange = { toggle(SettingToggle.CAMERA_SOUNDS, it) })
                SettingsItem(Icons.Outlined.SdStorage, "Device storage", "$freeSpace · Saved to DCIM/Camera")
                SettingsItem(Icons.Outlined.QrCodeScanner, "Scan QR codes & barcodes", "Outline codes in view and suggest an action (Photo mode). Also scan from an image. Decoded on-device.", prefs.scanQrCodes, onCheckedChange = { toggle(SettingToggle.SCAN_CODES, it) })
            }
        }
        item {
            SettingsGroup("Composition") {
                SettingsItem(Icons.Outlined.Straighten, "Framing hints", "Level in landscape and when shooting straight down", prefs.showLeveler, onCheckedChange = { toggle(SettingToggle.LEVELER, it) })
                SettingsItem(Icons.Outlined.GridOn, "Grid", prefs.gridMode.label, onClick = { dialog = SettingsDialog.GRID })
                SettingsItem(Icons.Outlined.Flip, "Keep selfies as seen", "Save selfies and front-camera videos mirrored, as they appear on screen", prefs.mirrorSelfies, onCheckedChange = { toggle(SettingToggle.MIRROR_SELFIES, it) })
            }
        }
        item {
            SettingsGroup("Fast access") {
                SettingsItem(Icons.AutoMirrored.Outlined.VolumeUp, "Volume key action", prefs.volumeKeyAction.label, onClick = { dialog = SettingsDialog.VOLUME })
                SettingsItem(Icons.Outlined.Cameraswitch, "Double-tap to switch camera", "Double-tap the viewfinder to flip between front and back", prefs.doubleTapSwitch, onCheckedChange = { toggle(SettingToggle.DOUBLE_TAP, it) })
                SettingsItem(Icons.Outlined.PanTool, "Palm timer", "Hold up an open hand next to your face to start a 3-second timer", prefs.gestureTrigger, onCheckedChange = { toggle(SettingToggle.PALM_TIMER, it) })
            }
        }
        item {
            SettingsGroup("Photo") {
                SettingsItem(Icons.Outlined.HdrOn, "HDR", "Capture an exposure bracket and merge it for more detail in highlights and shadows (slower). Rear camera, not with the flash or RAW + JPEG.", prefs.hdrPhoto, onCheckedChange = { toggle(SettingToggle.HDR, it) })
                SettingsItem(Icons.Outlined.PhotoSizeSelectLarge, "Resolution", "${prefs.resolution.label} · rear camera, Photo mode", onClick = { dialog = SettingsDialog.PHOTO_RESOLUTION })
                SettingsItem(Icons.Outlined.Photo, "File format", "${prefs.saveMode.label} · rear camera, Photo mode", onClick = { dialog = SettingsDialog.FILE_FORMAT })
                SettingsItem(Icons.Outlined.NightsStay, "Low-light suggestions", "Offer Night mode when the scene is dark", prefs.lowLightSuggestion, onCheckedChange = { toggle(SettingToggle.LOW_LIGHT, it) })
                SettingsItem(Icons.Outlined.Portrait, "Keep original portraits", "Also save Portrait photos without the background blur", prefs.portraitKeepOriginal, onCheckedChange = { toggle(SettingToggle.PORTRAIT_ORIGINAL, it) })
            }
        }
        item {
            SettingsGroup("Video") {
                SettingsItem(Icons.Outlined.HighQuality, "Resolution", prefs.videoResolution.value, onClick = { dialog = SettingsDialog.VIDEO_RESOLUTION })
                SettingsItem(Icons.Outlined.Speed, "Frame rate", prefs.videoFrameRate.label, onClick = { dialog = SettingsDialog.FRAME_RATE })
                SettingsItem(Icons.Outlined.Vibration, "Stabilization", "Reduce camera shake for smoother videos", prefs.videoStabilization, onCheckedChange = { toggle(SettingToggle.STABILIZATION, it) })
                SettingsItem(Icons.Outlined.Mic, "Record audio", null, prefs.recordAudio, onCheckedChange = { toggle(SettingToggle.AUDIO, it) })
            }
        }
        item {
            SettingsGroup("Advanced") {
                SettingsItem(Icons.Outlined.FlashlightOn, "Timer light", "Flash the rear light during the timer countdown", prefs.timerLight, onCheckedChange = { toggle(SettingToggle.TIMER_LIGHT, it) })
            }
        }

        item { AppInfoCard(versionName) }
        item {
            SettingsGroup("About") {
                SettingsItem(Icons.Outlined.Code, "Source code", "github.com/fearmikey/Camera", external = true, onClick = { actions.onOpenUrl(AppLinks.SOURCE_CODE) })
                SettingsItem(Icons.Outlined.NewReleases, "What's new", "Changelog for every release", external = true, onClick = { actions.onOpenUrl(AppLinks.CHANGELOG) })
                SettingsItem(Icons.Outlined.BugReport, "Report an issue", "Bugs and feature requests on GitHub", external = true, onClick = { actions.onOpenUrl(AppLinks.ISSUES) })
                SettingsItem(Icons.Outlined.Gavel, "License", "GNU General Public License v3.0 or later", onClick = { dialog = SettingsDialog.LICENSE })
                SettingsItem(Icons.Outlined.Description, "Open-source licenses", "OpenCV, AndroidX and Kotlin (Apache-2.0)", onClick = { dialog = SettingsDialog.OPEN_SOURCE_LICENSES })
            }
        }
        item { SupportCard(onClick = { actions.onOpenUrl(AppLinks.DONATE) }) }
    }
}

/** A titled section: header text above a rounded card holding the rows. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsHeader(title)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * One settings row. With [checked] + [onCheckedChange] it is a switch (the whole row toggles);
 * with [onClick] it opens a dialog, or a link when [external] (shows an open-in-new icon).
 */
@Composable
fun SettingsItem(
    icon: ImageVector?,
    title: String,
    description: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    external: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    val rowModifier = when {
        checked != null && onCheckedChange != null -> Modifier.toggleable(
            value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange,
        )
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
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
        when {
            checked != null -> Switch(
                checked = checked,
                onCheckedChange = null, // the row handles toggling (one accessibility node)
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
            external -> Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "Opens in browser",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** App identity at the top of the About section: icon, name, version and the FOSS promise. */
@Composable
private fun AppInfoCard(versionName: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            // Adaptive icon layers; the circle crops the 108dp canvas like a launcher mask.
            Box(modifier = Modifier.size(72.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Free & open source. No ads, no analytics, no network access. All processing happens on your device using OpenCV and the Android camera APIs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Highlighted Buy Me a Coffee link at the end of the list. */
@Composable
private fun SupportCard(onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsHeader("Support")
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.LocalCafe, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Buy me a coffee", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enjoying the app? A small tip keeps it free, ad-free and in active development.",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in browser", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LicenseDialog(onViewFull: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
        title = { Text("GNU GPL v3.0 or later") },
        text = {
            Text(
                text = "Copyright (C) 2026 the Camera contributors.\n\n" +
                    "This program is free software: you can redistribute it and/or modify it under the terms of the " +
                    "GNU General Public License as published by the Free Software Foundation, either version 3 of the " +
                    "License, or (at your option) any later version.\n\n" +
                    "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without " +
                    "even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU " +
                    "General Public License for more details.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onViewFull) { Text("Full license") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun OpenSourceLicensesDialog(onViewApache: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
        title = { Text("Open-source licenses") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("This app is built with these free software libraries:", style = MaterialTheme.typography.bodyMedium)
                ThirdPartyLicenses.forEach { (name, license) ->
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onViewApache) { Text("Apache 2.0 text") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Light", widthDp = 393, heightDp = 2900, showBackground = true)
@Preview(name = "Dark", widthDp = 393, heightDp = 2900, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsListPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            SettingsList(prefs = UserPreferences(), actions = SettingsActions())
        }
    }
}
