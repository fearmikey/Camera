package com.fearmikey.camera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import com.fearmikey.camera.domain.model.*
import com.fearmikey.camera.ui.camera.CameraUiState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.fearmikey.camera.domain.model.CaptureType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    userPreferences: UserPreferences,
    captureType: CaptureType,
    isFrontCamera: Boolean,
    onDismiss: () -> Unit,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onTimerChange: (TimerMode) -> Unit,
    onLightModeChange: (LightMode) -> Unit,
    onMacroFocusChange: (MacroMode) -> Unit,
    onTopShotToggle: (Boolean) -> Unit,
    onMotionPhotoChange: (MotionPhotoMode) -> Unit,
    onResolutionChange: (ResolutionMode) -> Unit,
    onSaveModeChange: (ImageSaveMode) -> Unit,
    onLensSelectionChange: (LensSelectionMode) -> Unit,
    onGridLinesToggle: () -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit,
    onVolumeKeyShutterToggle: () -> Unit,
    onGestureTriggerToggle: () -> Unit,
    onFullSettingsClick: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1C),
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (captureType == CaptureType.PHOTO) "Photo settings" else "Video settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
                IconButton(
                    onClick = onFullSettingsClick,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "More")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        Box(
                            Modifier
                                .tabIndicatorOffset(tabPositions[selectedTab])
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFFDDE4D7))
                        )
                    }
                },
                divider = {},
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .height(48.dp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "General",
                            color = if (selectedTab == 0) Color.Black else Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Pro",
                            color = if (selectedTab == 1) Color.Black else Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (selectedTab == 0) {
                GeneralSettings(userPreferences, isFrontCamera, onAspectRatioChange, onTimerChange, onLightModeChange, onMacroFocusChange, onTopShotToggle, onMotionPhotoChange, onGestureTriggerToggle)
            } else {
                ProSettings(userPreferences, onResolutionChange, onSaveModeChange, onLensSelectionChange, onGridLinesToggle, onLevelerToggle, onLocationTaggingToggle, onVolumeKeyShutterToggle)
            }
        }
    }
}

@Composable
fun GeneralSettings(
    userPreferences: UserPreferences,
    isFrontCamera: Boolean,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onTimerChange: (TimerMode) -> Unit,
    onLightModeChange: (LightMode) -> Unit,
    onMacroFocusChange: (MacroMode) -> Unit,
    onTopShotToggle: (Boolean) -> Unit,
    onMotionPhotoChange: (MotionPhotoMode) -> Unit,
    onGestureTriggerToggle: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingRowWithIcons(
                label = "More light",
                currentValue = userPreferences.lightMode,
                options = LightMode.entries,
                onOptionSelected = onLightModeChange,
                iconProvider = { mode, tint ->
                    when (mode) {
                        LightMode.NONE -> Icon(Icons.Default.Block, null, tint = tint, modifier = Modifier.size(20.dp))
                        LightMode.AUTO_NIGHT_SIGHT -> Icon(Icons.Default.AutoMode, null, tint = tint, modifier = Modifier.size(20.dp))
                        LightMode.FLASH -> Icon(Icons.Default.FlashOn, null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
        item {
            SettingRowWithIcons(
                label = "Top Shot",
                currentValue = userPreferences.topShot,
                options = listOf(false, true),
                onOptionSelected = onTopShotToggle,
                iconProvider = { enabled, tint ->
                    Icon(
                        imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Block,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
        item {
            SettingRowWithIcons(
                label = "Motion Photo",
                currentValue = userPreferences.motionPhoto,
                options = MotionPhotoMode.entries,
                onOptionSelected = onMotionPhotoChange,
                iconProvider = { mode, tint ->
                    when (mode) {
                        MotionPhotoMode.OFF -> Icon(Icons.Default.Block, null, tint = tint, modifier = Modifier.size(20.dp))
                        MotionPhotoMode.AUTO -> Icon(Icons.Default.AutoMode, null, tint = tint, modifier = Modifier.size(20.dp))
                        MotionPhotoMode.ON -> Icon(Icons.Default.MotionPhotosOn, null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
        item {
            SettingRowWithIcons(
                label = "Timer",
                currentValue = userPreferences.timer,
                options = TimerMode.entries,
                onOptionSelected = onTimerChange,
                iconProvider = { mode, tint ->
                    when (mode) {
                        TimerMode.OFF -> Icon(Icons.Default.TimerOff, null, tint = tint, modifier = Modifier.size(20.dp))
                        TimerMode.SEC_3 -> Text("3", color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        TimerMode.SEC_5 -> Text("5", color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        TimerMode.SEC_10 -> Text("10", color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            )
        }
        if (!isFrontCamera) {
            item {
                SettingRowWithIcons(
                    label = "Macro Focus",
                    currentValue = userPreferences.macroFocus,
                    options = MacroMode.entries,
                    onOptionSelected = onMacroFocusChange,
                    iconProvider = { mode, tint ->
                        when (mode) {
                            MacroMode.OFF -> Icon(Icons.Default.Block, null, tint = tint, modifier = Modifier.size(20.dp))
                            MacroMode.AUTO -> Icon(Icons.Default.AutoMode, null, tint = tint, modifier = Modifier.size(20.dp))
                            MacroMode.ON -> Icon(Icons.Default.FilterVintage, null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }
        item {
            SettingRowWithIcons(
                label = "Ratio",
                currentValue = userPreferences.aspectRatio,
                options = AspectRatio.entries,
                onOptionSelected = onAspectRatioChange,
                iconProvider = { ratio, tint ->
                    when (ratio) {
                        AspectRatio.RATIO_4_3 -> Text("4:3", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        AspectRatio.RATIO_16_9 -> Text("16:9", color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        AspectRatio.RATIO_FULL -> Icon(Icons.Default.CropFree, null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
        item {
            SettingsSwitchRow("Palm timer", userPreferences.gestureTrigger, onGestureTriggerToggle)
        }
    }
}

@Composable
fun ProSettings(
    userPreferences: UserPreferences,
    onResolutionChange: (ResolutionMode) -> Unit,
    onSaveModeChange: (ImageSaveMode) -> Unit,
    onLensSelectionChange: (LensSelectionMode) -> Unit,
    onGridLinesToggle: () -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit,
    onVolumeKeyShutterToggle: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingRowWithIcons(
                label = "Resolution",
                currentValue = userPreferences.resolution,
                options = ResolutionMode.entries,
                onOptionSelected = onResolutionChange,
                iconProvider = { res, tint ->
                    Text(
                        text = if (res == ResolutionMode.MP_12) "12" else "50",
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            )
        }
        item {
            SettingRowWithIcons(
                label = "RAW / JPEG",
                currentValue = userPreferences.saveMode,
                options = ImageSaveMode.entries,
                onOptionSelected = onSaveModeChange,
                iconProvider = { mode, tint ->
                    if (mode == ImageSaveMode.JPEG) {
                        Icon(Icons.Default.Block, null, tint = tint, modifier = Modifier.size(20.dp))
                    } else {
                        Text("RAW", color = tint, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            )
        }
        item {
            SettingRowWithIcons(
                label = "Lens Selection",
                currentValue = userPreferences.lensSelection,
                options = LensSelectionMode.entries,
                onOptionSelected = onLensSelectionChange,
                iconProvider = { mode, tint ->
                    if (mode == LensSelectionMode.AUTO) {
                        Text("A", color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    } else {
                        Icon(Icons.Default.FilterVintage, null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
        item {
            val gridLabel = when (userPreferences.gridMode) {
                GridMode.OFF -> "Off"
                GridMode.GRID_3X3 -> "3x3"
                GridMode.GRID_4X4 -> "4x4"
                GridMode.GOLDEN_RATIO -> "Golden"
            }
            SettingsRow("Grid Lines", gridLabel, onGridLinesToggle)
            SettingsSwitchRow("Horizon Leveler", userPreferences.showLeveler, onLevelerToggle)
            SettingsSwitchRow("Location Tagging", userPreferences.locationTagging, onLocationTaggingToggle)
            SettingsSwitchRow("Volume Key Shutter", userPreferences.volumeKeyShutter, onVolumeKeyShutterToggle)
        }
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
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subLabel != null) {
                Text(subLabel, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            } else {
                val valueText = when(currentValue) {
                    is Boolean -> if (currentValue) "On" else "Off"
                    is AspectRatio -> currentValue.value
                    is TimerMode -> if (currentValue == TimerMode.OFF) "Off" else "${currentValue.seconds} seconds"
                    else -> currentValue.toString().lowercase().replaceFirstChar { it.uppercase() }
                }
                Text(valueText, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
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
                        .background(if (isSelected) Color(0xFFDDE4D7) else Color.Transparent)
                ) {
                    iconProvider(option, if (isSelected) Color.Black else Color.White)
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Color.Yellow,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Yellow,
                checkedTrackColor = Color.Yellow.copy(alpha = 0.5f)
            )
        )
    }
}

inline fun <reified T : Enum<T>> nextEnum(current: T): T {
    val values = enumValues<T>()
    val nextIndex = (current.ordinal + 1) % values.size
    return values[nextIndex]
}
