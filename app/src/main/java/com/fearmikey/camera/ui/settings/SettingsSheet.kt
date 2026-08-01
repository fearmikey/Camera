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
import com.fearmikey.camera.domain.model.*
import com.fearmikey.camera.ui.camera.CameraUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    uiState: CameraUiState,
    onDismiss: () -> Unit,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onVideoResolutionChange: (VideoResolution) -> Unit,
    onImageFormatChange: (ImageFormat) -> Unit,
    onVideoCodecChange: (VideoCodec) -> Unit,
    onGridLinesToggle: () -> Unit,
    onLevelerToggle: () -> Unit,
    onLocationTaggingToggle: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black.copy(alpha = 0.9f),
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                SettingsSection("Capture")
                SettingsRow("Aspect Ratio", uiState.userPreferences.aspectRatio.value) {
                    onAspectRatioChange(nextEnum(uiState.userPreferences.aspectRatio))
                }
                SettingsRow("Image Format", uiState.userPreferences.imageFormat.value) {
                    onImageFormatChange(nextEnum(uiState.userPreferences.imageFormat))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                SettingsSection("Video")
                SettingsRow("Resolution", uiState.userPreferences.videoResolution.value) {
                    onVideoResolutionChange(nextEnum(uiState.userPreferences.videoResolution))
                }
                SettingsRow("Codec", uiState.userPreferences.videoCodec.value) {
                    onVideoCodecChange(nextEnum(uiState.userPreferences.videoCodec))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                SettingsSection("General")
                SettingsSwitchRow("Grid Lines", uiState.userPreferences.showGridLines, onGridLinesToggle)
                SettingsSwitchRow("Horizon Leveler", uiState.userPreferences.showLeveler, onLevelerToggle)
                SettingsSwitchRow("Location Tagging", uiState.userPreferences.locationTagging, onLocationTaggingToggle)
                Spacer(modifier = Modifier.height(32.dp))
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
