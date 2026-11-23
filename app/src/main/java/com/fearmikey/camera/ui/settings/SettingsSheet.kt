package com.fearmikey.camera.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.domain.model.*
import com.fearmikey.camera.ui.camera.TextBadge
import com.fearmikey.camera.ui.theme.ViewfinderTheme

/** Translucent near-black sheet: the viewfinder stays faintly visible behind it. */
private val SheetColor = Color(0xFF0B0B0C).copy(alpha = 0.92f)

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
    // Always dark, like the rest of the camera screen (accent still follows Material You).
    ViewfinderTheme {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetColor,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.25f),
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.45f)) },
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
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${CameraModes.label(modeCaps.mode)} settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(width = 52.dp, height = 40.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .semantics { contentDescription = "All settings"; role = Role.Button }
                    .clickable(onClick = onFullSettingsClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }

        // Slow motion / Time lapse have only a few settings: one page, no tabs.
        val singlePage = modeCaps.isVideo && !modeCaps.showsVideoQualitySettings
        if (singlePage) {
            Spacer(modifier = Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                VideoSettings(
                    userPreferences = userPreferences,
                    modeCaps = modeCaps,
                    onLightModeChange = onLightModeChange,
                    onVideoResolutionChange = onVideoResolutionChange,
                    onVideoStabilizationToggle = onVideoStabilizationToggle,
                    onVideoFrameRateChange = onVideoFrameRateChange,
                )
                MoreSettings(
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
                Text(
                    "${CameraModes.label(modeCaps.mode)} records without sound.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        } else {
        Spacer(modifier = Modifier.height(20.dp))
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
}

/** Two side-by-side tabs: the selected one is an accent pill, the other a dark rounded box. */
@Composable
private fun TabSwitcher(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = selected == i
            val corner by animateDpAsState(if (isSelected) 20.dp else 12.dp, tween(200), label = "tabCorner")
            val container by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                tween(200), label = "tabBg"
            )
            val content by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                tween(200), label = "tabFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(container)
                    .semantics { this.selected = isSelected; role = Role.Tab }
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = content, style = MaterialTheme.typography.bodyLarge)
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
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        val lightMode = modeCaps.effectiveLightMode
        if (lightMode != null) {
            SettingRowWithIcons(
                label = "Flash",
                value = lightMode.label,
                caption = if (lightMode == LightMode.AUTO_NIGHT_SIGHT) "No flash, Night shot when it's dark" else null,
                currentValue = lightMode,
                options = modeCaps.lightModes,
                onOptionSelected = onLightModeChange,
                optionDescription = { it.label },
                iconProvider = { mode, tint, _ ->
                    val icon = when (mode) {
                        LightMode.NONE -> Icons.Default.FlashOff
                        LightMode.AUTO_NIGHT_SIGHT -> Icons.Default.NightsStay
                        LightMode.AUTO_FLASH -> Icons.Default.FlashAuto
                        LightMode.FLASH -> Icons.Default.FlashOn
                    }
                    OptionIcon(icon, tint)
                }
            )
        }
        if (modeCaps.supportsTimer) {
            SettingRowWithIcons(
                label = "Timer",
                value = if (userPreferences.timer == TimerMode.OFF) "Off" else "${userPreferences.timer.seconds} seconds",
                currentValue = userPreferences.timer,
                options = TimerMode.selectable,
                onOptionSelected = onTimerChange,
                optionDescription = { if (it == TimerMode.OFF) "Timer off" else "${it.seconds} second timer" },
                iconProvider = { mode, tint, _ ->
                    when (mode) {
                        TimerMode.OFF -> OptionIcon(Icons.Default.TimerOff, tint)
                        TimerMode.SEC_3 -> OptionIcon(Icons.Default.Timer3, tint)
                        TimerMode.SEC_10 -> OptionIcon(Icons.Default.Timer10, tint)
                        else -> Text("${mode.seconds}", color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            )
        }
        if (modeCaps.showsMacro) {
            SettingRowWithIcons(
                label = "Macro focus",
                value = if (userPreferences.macroFocus == MacroMode.ON) "On" else "Off",
                caption = if (userPreferences.macroFocus == MacroMode.ON) "Focuses very close" else null,
                currentValue = userPreferences.macroFocus,
                options = MacroMode.entries,
                onOptionSelected = onMacroFocusChange,
                optionDescription = { if (it == MacroMode.ON) "Macro on" else "Macro off" },
                iconProvider = { mode, tint, _ ->
                    OptionIcon(if (mode == MacroMode.ON) Icons.Default.LocalFlorist else Icons.Default.Block, tint)
                }
            )
        }
        SettingRowWithIcons(
            label = "Ratio",
            value = when (userPreferences.aspectRatio) {
                AspectRatio.RATIO_4_3 -> "Full image (4:3)"
                AspectRatio.RATIO_16_9 -> "Wide (16:9)"
                AspectRatio.RATIO_FULL -> "Full screen"
            },
            currentValue = userPreferences.aspectRatio,
            options = AspectRatio.entries,
            onOptionSelected = onAspectRatioChange,
            optionDescription = { if (it == AspectRatio.RATIO_FULL) "Full screen" else it.value },
            iconProvider = { ratio, tint, _ ->
                when (ratio) {
                    AspectRatio.RATIO_4_3 -> RatioGlyph(16.dp, 21.dp, tint)
                    AspectRatio.RATIO_16_9 -> RatioGlyph(12.dp, 21.dp, tint)
                    AspectRatio.RATIO_FULL -> OptionIcon(Icons.Default.CropFree, tint)
                }
            }
        )
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
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        if (modeCaps.effectiveLightMode != null) {
            val torchOn = modeCaps.effectiveLightMode == LightMode.FLASH
            ToggleIconRow(
                label = "Flash",
                checked = torchOn,
                offIcon = Icons.Default.FlashOff,
                onIcon = Icons.Default.FlashOn,
                onCheckedChange = { on -> onLightModeChange(if (on) LightMode.FLASH else LightMode.NONE) },
            )
        }
        if (modeCaps.showsVideoQualitySettings) {
            SettingRowWithIcons(
                label = "Resolution",
                value = if (userPreferences.videoResolution == VideoResolution.RES_4K) "4K (Ultra HD)" else "1080p (Full HD)",
                currentValue = userPreferences.videoResolution,
                options = VideoResolution.entries,
                onOptionSelected = onVideoResolutionChange,
                optionDescription = { it.value },
                iconProvider = { res, tint, container ->
                    TextBadge(listOf(if (res == VideoResolution.RES_4K) "4K" else "FHD"), color = tint, contentColor = container)
                }
            )
            SettingRowWithIcons(
                label = "Frame rate",
                value = userPreferences.videoFrameRate.fps?.toString() ?: "Auto",
                caption = if (userPreferences.videoFrameRate != VideoFrameRate.AUTO) "When the camera supports it" else null,
                currentValue = userPreferences.videoFrameRate,
                options = VideoFrameRate.entries,
                onOptionSelected = onVideoFrameRateChange,
                optionDescription = { it.label },
                iconProvider = { rate, tint, _ ->
                    if (rate.fps == null) {
                        OptionIcon(Icons.Default.HdrAuto, tint)
                    } else {
                        Text(rate.fps.toString(), color = tint, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                }
            )
            ToggleIconRow(
                label = "Stabilization",
                checked = userPreferences.videoStabilization,
                offIcon = Icons.Default.Block,
                onIcon = Icons.Default.VideoStable,
                onCheckedChange = { onVideoStabilizationToggle() },
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
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        if (modeCaps.showsVideoQualitySettings) {
            ToggleIconRow(
                label = "Record audio",
                checked = userPreferences.recordAudio,
                offIcon = Icons.Default.MicOff,
                onIcon = Icons.Default.Mic,
                onCheckedChange = onRecordAudioToggle,
            )
        }
        if (modeCaps.showsPhotoOutputSettings) {
            SettingRowWithIcons(
                label = "Resolution",
                // Same badges as the top-bar chip.
                value = if (userPreferences.resolution == ResolutionMode.MP_12) "12MP" else "Full resolution",
                currentValue = userPreferences.resolution,
                options = ResolutionMode.entries,
                onOptionSelected = onResolutionChange,
                optionDescription = { it.label },
                iconProvider = { res, tint, container ->
                    TextBadge(
                        if (res == ResolutionMode.MP_12) listOf("12", "MP") else listOf("MAX"),
                        color = tint, contentColor = container,
                    )
                }
            )
            SettingRowWithIcons(
                label = "File format",
                value = if (userPreferences.saveMode == ImageSaveMode.JPEG) "JPEG only" else "RAW + JPEG",
                currentValue = userPreferences.saveMode,
                options = ImageSaveMode.entries,
                onOptionSelected = onSaveModeChange,
                optionDescription = { it.label },
                iconProvider = { mode, tint, container ->
                    TextBadge(listOf(if (mode == ImageSaveMode.JPEG) "JPG" else "RAW"), color = tint, contentColor = container)
                }
            )
            ToggleIconRow(
                label = "HDR",
                checked = userPreferences.hdrPhoto,
                offIcon = Icons.Default.HdrOff,
                onIcon = Icons.Default.HdrOn,
                caption = when {
                    !userPreferences.hdrPhoto -> null
                    userPreferences.saveMode == ImageSaveMode.RAW_JPEG -> "Not used with RAW + JPEG"
                    modeCaps.hdrPausedByFlash -> "Not used while the flash is on"
                    else -> null
                },
                onCheckedChange = onHdrToggle,
            )
        }
        SettingRowWithIcons(
            label = "Grid",
            value = userPreferences.gridMode.label,
            currentValue = userPreferences.gridMode,
            options = GridMode.entries,
            onOptionSelected = onGridModeChange,
            optionDescription = { it.label },
            iconProvider = { mode, tint, _ ->
                when (mode) {
                    GridMode.OFF -> OptionIcon(Icons.Default.GridOff, tint)
                    GridMode.GRID_3X3 -> OptionIcon(Icons.Default.Grid3x3, tint)
                    GridMode.GRID_4X4 -> OptionIcon(Icons.Default.Grid4x4, tint)
                    GridMode.GOLDEN_RATIO -> Text("φ", color = tint, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        )
        ToggleIconRow(
            label = "Framing hints",
            checked = userPreferences.showLeveler,
            offIcon = Icons.Default.Block,
            onIcon = Icons.Default.Straighten,
            caption = "Level in landscape and when shooting straight down",
            onCheckedChange = { onLevelerToggle() },
        )
        ToggleIconRow(
            label = "Save location",
            checked = userPreferences.locationTagging,
            offIcon = Icons.Default.LocationOff,
            onIcon = Icons.Default.LocationOn,
            onCheckedChange = { onLocationTaggingToggle() },
        )
    }
}

private val RowGap = 14.dp
private val OptionSize = 40.dp

/**
 * One setting: a small label with the current value in large accent text on the left, and
 * one button per option on the right (dark rounded squares; the selected one is an accent
 * circle). [iconProvider] draws an option's glyph given (option, content color, container color).
 */
@Composable
fun <T> SettingRowWithIcons(
    label: String,
    value: String,
    currentValue: T,
    options: List<T>,
    onOptionSelected: (T) -> Unit,
    optionDescription: (T) -> String,
    caption: String? = null,
    iconProvider: @Composable (T, Color, Color) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                OptionButton(
                    selected = option == currentValue,
                    description = optionDescription(option),
                    onClick = { onOptionSelected(option) },
                ) { content, container -> iconProvider(option, content, container) }
            }
        }
    }
}

/** Off/On setting drawn with two icon buttons. */
@Composable
private fun ToggleIconRow(
    label: String,
    checked: Boolean,
    offIcon: ImageVector,
    onIcon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
    caption: String? = null,
) {
    SettingRowWithIcons(
        label = label,
        value = if (checked) "On" else "Off",
        caption = caption,
        currentValue = checked,
        options = listOf(false, true),
        onOptionSelected = onCheckedChange,
        optionDescription = { if (it) "$label on" else "$label off" },
        iconProvider = { on, tint, _ -> OptionIcon(if (on) onIcon else offIcon, tint) },
    )
}

@Composable
private fun OptionButton(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable (Color, Color) -> Unit,
) {
    val corner by animateDpAsState(if (selected) OptionSize / 2 else 12.dp, tween(200), label = "optionCorner")
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(200), label = "optionBg"
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        tween(200), label = "optionFg"
    )
    Box(
        modifier = Modifier
            .size(OptionSize)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .semantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(tint, container)
    }
}

@Composable
private fun OptionIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/** Outlined frame with the photo's shape (4:3 / 16:9 in portrait). */
@Composable
private fun RatioGlyph(width: Dp, height: Dp, tint: Color) {
    Box(Modifier.size(width, height).border(2.dp, tint, RoundedCornerShape(3.dp)))
}

@Preview(widthDp = 412, heightDp = 620, showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun PhotoSettingsSheetPreview() {
    ViewfinderTheme {
        Surface(color = SheetColor, contentColor = Color.White) {
            val prefs = UserPreferences(timer = TimerMode.SEC_3, lightMode = LightMode.AUTO_NIGHT_SIGHT)
            SettingsSheetContent(
                prefs, ModeCapabilities.forMode(CameraMode.PHOTO, isFrontCamera = false, prefs),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 620, showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun MoreSettingsPreview() {
    ViewfinderTheme {
        Surface(color = SheetColor, contentColor = Color.White) {
            val prefs = UserPreferences(hdrPhoto = true, lightMode = LightMode.FLASH, gridMode = GridMode.GRID_3X3)
            Box(Modifier.padding(16.dp)) {
                MoreSettings(
                    prefs, ModeCapabilities.forMode(CameraMode.PHOTO, isFrontCamera = false, prefs),
                    {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
    }
}

@Preview(widthDp = 412, heightDp = 520, showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun SlowMotionSettingsSheetPreview() {
    ViewfinderTheme {
        Surface(color = SheetColor, contentColor = Color.White) {
            val prefs = UserPreferences()
            SettingsSheetContent(
                prefs, ModeCapabilities.forMode(CameraMode.SLOW_MO, isFrontCamera = false, prefs),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 520, showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun VideoSettingsSheetPreview() {
    ViewfinderTheme {
        Surface(color = SheetColor, contentColor = Color.White) {
            val prefs = UserPreferences()
            SettingsSheetContent(
                prefs, ModeCapabilities.forMode(CameraMode.VIDEO, isFrontCamera = false, prefs),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
            )
        }
    }
}
