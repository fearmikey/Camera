package com.fearmikey.camera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.domain.model.*

import com.fearmikey.camera.ui.theme.CameraAppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    userPreferences: UserPreferences,
    /** What the current mode and camera support; rows that wouldn't apply are not shown. */
    modeCaps: ModeCapabilities,
    onDismiss: () -> Unit,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onTimerChange: (TimerMode) -> Unit,
    onLightModeChange: (LightMode) -> Unit,
    onMacroFocusChange: (MacroMode) -> Unit,
    onResolutionChange: (ResolutionMode) -> Unit,
    onSaveModeChange: (ImageSaveMode) -> Unit,
    onVideoResolutionChange: (VideoResolution) -> Unit,
    onVideoStabilizationToggle: () -> Unit,
    onVideoFrameRateChange: (VideoFrameRate) -> Unit,
    onHdrToggle: (Boolean) -> Unit,
    onRecordAudioToggle: (Boolean) -> Unit,
    onGridModeChange: (GridMode) -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit,
    onFullSettingsClick: () -> Unit
) {
    // The sheet follows the system light/dark theme (the viewfinder behind it stays dark).
    CameraAppTheme {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SettingsSheetContent(
            userPreferences, modeCaps,
            onAspectRatioChange, onTimerChange, onLightModeChange, onMacroFocusChange,
            onResolutionChange, onSaveModeChange, onVideoResolutionChange, onVideoStabilizationToggle,
            onVideoFrameRateChange, onHdrToggle, onRecordAudioToggle,
            onGridModeChange, onLevelerToggle, onLocationTaggingToggle,
            onFullSettingsClick
        )
    }
    }
}

@Composable
fun SettingsSheetContent(
    userPreferences: UserPreferences,
    modeCaps: ModeCapabilities,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onTimerChange: (TimerMode) -> Unit,
    onLightModeChange: (LightMode) -> Unit,
    onMacroFocusChange: (MacroMode) -> Unit,
    onResolutionChange: (ResolutionMode) -> Unit,
    onSaveModeChange: (ImageSaveMode) -> Unit,
    onVideoResolutionChange: (VideoResolution) -> Unit,
    onVideoStabilizationToggle: () -> Unit,
    onVideoFrameRateChange: (VideoFrameRate) -> Unit,
    onHdrToggle: (Boolean) -> Unit,
    onRecordAudioToggle: (Boolean) -> Unit,
    onGridModeChange: (GridMode) -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit,
    onFullSettingsClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${CameraModes.label(modeCaps.mode)} settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
            IconButton(
                onClick = onFullSettingsClick,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "All settings")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        TabSwitcher(selectedTab, listOf("General", "More")) { selectedTab = it }
        Spacer(modifier = Modifier.height(24.dp))

        when {
            selectedTab == 1 -> MoreSettings(
                userPreferences = userPreferences,
                modeCaps = modeCaps,
                onResolutionChange = onResolutionChange,
                onSaveModeChange = onSaveModeChange,
                onHdrToggle = onHdrToggle,
                onRecordAudioToggle = onRecordAudioToggle,
                onGridModeChange = onGridModeChange,
                onLevelerToggle = onLevelerToggle,
                onLocationTaggingToggle = onLocationTaggingToggle,
            )
            modeCaps.isVideo -> VideoSettings(
                userPreferences = userPreferences,
                modeCaps = modeCaps,
                onLightModeChange = onLightModeChange,
                onVideoResolutionChange = onVideoResolutionChange,
                onVideoStabilizationToggle = onVideoStabilizationToggle,
                onVideoFrameRateChange = onVideoFrameRateChange,
            )
            else -> PhotoSettings(
                userPreferences = userPreferences,
                modeCaps = modeCaps,
                onAspectRatioChange = onAspectRatioChange,
                onTimerChange = onTimerChange,
                onLightModeChange = onLightModeChange,
                onMacroFocusChange = onMacroFocusChange,
            )
        }
    }
}

@Composable
private fun TabSwitcher(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        labels.forEachIndexed { i, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected == i) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected == i) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PhotoSettings(
    userPreferences: UserPreferences,
    modeCaps: ModeCapabilities,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onTimerChange: (TimerMode) -> Unit,
    onLightModeChange: (LightMode) -> Unit,
    onMacroFocusChange: (MacroMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val lightMode = modeCaps.effectiveLightMode
        if (lightMode != null) {
            SettingRowWithIcons(
                label = "Flash",
                subLabel = when (lightMode) {
                    LightMode.AUTO_NIGHT_SIGHT -> "Auto Night: no flash, Night shot when dark"
                    else -> lightMode.label
                },
                currentValue = lightMode,
                options = modeCaps.lightModes,
                onOptionSelected = onLightModeChange,
                iconProvider = { mode, tint ->
                    val icon = when (mode) {
                        LightMode.NONE -> Icons.Default.FlashOff
                        LightMode.AUTO_NIGHT_SIGHT -> Icons.Default.NightsStay
                        LightMode.AUTO_FLASH -> Icons.Default.FlashAuto
                        LightMode.FLASH -> Icons.Default.FlashOn
                    }
                    Icon(icon, mode.label, tint = tint, modifier = Modifier.size(20.dp))
                }
            )
        }
        if (modeCaps.supportsTimer) {
            SettingRowWithIcons(
                label = "Timer",
                currentValue = userPreferences.timer,
                options = TimerMode.selectable,
                onOptionSelected = onTimerChange,
                iconProvider = { mode, tint ->
                    when (mode) {
                        TimerMode.OFF -> Icon(Icons.Default.TimerOff, "Timer off", tint = tint, modifier = Modifier.size(20.dp))
                        else -> Text("${mode.seconds}", color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            )
        }
        SettingRowWithIcons(
            label = "Ratio",
            currentValue = userPreferences.aspectRatio,
            options = AspectRatio.entries,
            onOptionSelected = onAspectRatioChange,
            iconProvider = { ratio, tint ->
                when (ratio) {
                    AspectRatio.RATIO_4_3 -> Text("4:3", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    AspectRatio.RATIO_16_9 -> Text("16:9", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    AspectRatio.RATIO_FULL -> Icon(Icons.Default.CropFree, "Full screen", tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        )
        if (modeCaps.showsMacro) {
            SettingRowWithIcons(
                label = "Macro focus",
                subLabel = if (userPreferences.macroFocus == MacroMode.ON) "On: focuses very close" else "Off",
                currentValue = userPreferences.macroFocus,
                options = MacroMode.entries,
                onOptionSelected = onMacroFocusChange,
                iconProvider = { mode, tint ->
                    if (mode == MacroMode.ON) {
                        Icon(Icons.Default.LocalFlorist, "Macro on", tint = tint, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Block, "Macro off", tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    }
}

@Composable
fun VideoSettings(
    userPreferences: UserPreferences,
    modeCaps: ModeCapabilities,
    onLightModeChange: (LightMode) -> Unit,
    onVideoResolutionChange: (VideoResolution) -> Unit,
    onVideoStabilizationToggle: () -> Unit,
    onVideoFrameRateChange: (VideoFrameRate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (modeCaps.showsVideoQualitySettings) {
            SettingRowWithIcons(
                label = "Resolution",
                subLabel = userPreferences.videoResolution.value,
                currentValue = userPreferences.videoResolution,
                options = VideoResolution.entries,
                onOptionSelected = onVideoResolutionChange,
                iconProvider = { res, tint ->
                    Text(if (res == VideoResolution.RES_4K) "4K" else "HD", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            )
        }
        if (modeCaps.effectiveLightMode != null) {
            val torchOn = modeCaps.effectiveLightMode == LightMode.FLASH
            SettingRowWithIcons(
                label = "Torch",
                subLabel = if (torchOn) "On" else "Off",
                currentValue = torchOn,
                options = listOf(false, true),
                onOptionSelected = { on -> onLightModeChange(if (on) LightMode.FLASH else LightMode.NONE) },
                iconProvider = { on, tint ->
                    Icon(if (on) Icons.Default.FlashOn else Icons.Default.FlashOff, if (on) "Torch on" else "Torch off", tint = tint, modifier = Modifier.size(20.dp))
                }
            )
        }
        if (modeCaps.showsVideoQualitySettings) {
            SettingRowWithIcons(
                label = "Frame rate",
                subLabel = userPreferences.videoFrameRate.label + if (userPreferences.videoFrameRate != VideoFrameRate.AUTO) " (if supported)" else "",
                currentValue = userPreferences.videoFrameRate,
                options = VideoFrameRate.entries,
                onOptionSelected = onVideoFrameRateChange,
                iconProvider = { rate, tint ->
                    Text(rate.fps?.toString() ?: "A", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            )
            SettingsSwitchRow("Stabilization", userPreferences.videoStabilization, onToggle = onVideoStabilizationToggle)
        } else {
            Text(
                "${CameraModes.label(modeCaps.mode)} records without sound at up to 1080p. Resolution, " +
                    "frame rate, stabilization and audio settings apply to Video mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MoreSettings(
    userPreferences: UserPreferences,
    modeCaps: ModeCapabilities,
    onResolutionChange: (ResolutionMode) -> Unit,
    onSaveModeChange: (ImageSaveMode) -> Unit,
    onHdrToggle: (Boolean) -> Unit,
    onRecordAudioToggle: (Boolean) -> Unit,
    onGridModeChange: (GridMode) -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (modeCaps.showsVideoQualitySettings) {
            SettingsSwitchRow("Record audio", userPreferences.recordAudio) { onRecordAudioToggle(!userPreferences.recordAudio) }
        }
        if (modeCaps.showsPhotoOutputSettings) {
            SettingsSwitchRow(
                "HDR",
                userPreferences.hdrPhoto,
                subtitle = when {
                    !userPreferences.hdrPhoto -> null
                    userPreferences.saveMode == ImageSaveMode.RAW_JPEG -> "Not used with RAW + JPEG"
                    modeCaps.hdrPausedByFlash -> "Not used while the flash is on"
                    else -> null
                },
            ) { onHdrToggle(!userPreferences.hdrPhoto) }
            SettingRowWithIcons(
                label = "Resolution",
                currentValue = userPreferences.resolution,
                options = ResolutionMode.entries,
                onOptionSelected = onResolutionChange,
                iconProvider = { res, tint ->
                    // Same labels as the top-bar chip.
                    Text(if (res == ResolutionMode.MP_12) "12MP" else "MAX", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            )
            SettingRowWithIcons(
                label = "File format",
                currentValue = userPreferences.saveMode,
                options = ImageSaveMode.entries,
                onOptionSelected = onSaveModeChange,
                iconProvider = { mode, tint ->
                    Text(if (mode == ImageSaveMode.JPEG) "JPG" else "RAW", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            )
        }
        SettingRowWithIcons(
            label = "Grid",
            subLabel = userPreferences.gridMode.label,
            currentValue = userPreferences.gridMode,
            options = GridMode.entries,
            onOptionSelected = onGridModeChange,
            iconProvider = { mode, tint ->
                when (mode) {
                    GridMode.OFF -> Icon(Icons.Default.GridOff, mode.label, tint = tint, modifier = Modifier.size(20.dp))
                    GridMode.GRID_3X3 -> Icon(Icons.Default.Grid3x3, mode.label, tint = tint, modifier = Modifier.size(20.dp))
                    GridMode.GRID_4X4 -> Icon(Icons.Default.Grid4x4, mode.label, tint = tint, modifier = Modifier.size(20.dp))
                    GridMode.GOLDEN_RATIO -> Text("φ", color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        )
        SettingsSwitchRow(
            "Framing hints",
            userPreferences.showLeveler,
            subtitle = "Level in landscape and when shooting straight down",
            onToggle = onLevelerToggle,
        )
        SettingsSwitchRow("Save location", userPreferences.locationTagging, onToggle = onLocationTaggingToggle)
    }
}

@Composable
fun <T> SettingRowWithIcons(
    label: String,
    subLabel: String? = null,
    currentValue: T,
    options: List<T>,
    onOptionSelected: (T) -> Unit,
    iconProvider: @Composable (T, Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val valueText = subLabel ?: when (currentValue) {
                is Boolean -> if (currentValue) "On" else "Off"
                is AspectRatio -> currentValue.value
                is LightMode -> currentValue.label
                is ResolutionMode -> currentValue.label
                is ImageSaveMode -> currentValue.label
                is GridMode -> currentValue.label
                is TimerMode -> if (currentValue == TimerMode.OFF) "Off" else "${currentValue.seconds} seconds"
                else -> currentValue.toString().lowercase().replaceFirstChar { it.uppercase() }
            }
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == currentValue
                IconButton(
                    onClick = { onOptionSelected(option) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    iconProvider(
                        option,
                        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, subtitle: String? = null, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Preview(name = "Light", widthDp = 380, heightDp = 560)
@Preview(name = "Dark", widthDp = 380, heightDp = 560, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PhotoSettingsSheetPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            val prefs = UserPreferences(timer = TimerMode.SEC_3)
            SettingsSheetContent(
                prefs, ModeCapabilities.forMode(CameraMode.PHOTO, isFrontCamera = false, prefs),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
            )
        }
    }
}

@Preview(widthDp = 380, heightDp = 560)
@Composable
private fun MoreSettingsPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            val prefs = UserPreferences(hdrPhoto = true, lightMode = LightMode.FLASH, gridMode = GridMode.GRID_3X3)
            Box(Modifier.padding(24.dp)) {
                MoreSettings(
                    prefs, ModeCapabilities.forMode(CameraMode.PHOTO, isFrontCamera = false, prefs),
                    {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
    }
}

@Preview(widthDp = 380, heightDp = 420)
@Composable
private fun VideoSettingsSheetPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            val prefs = UserPreferences()
            SettingsSheetContent(
                prefs, ModeCapabilities.forMode(CameraMode.VIDEO, isFrontCamera = false, prefs),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
            )
        }
    }
}
