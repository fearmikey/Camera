package com.fearmikey.camera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.domain.model.ProConfig

@Composable
fun ProControlOverlay(
    uiState: CameraUiState,
    onConfigChange: (ProConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val capabilities = uiState.capabilities ?: return
    val config = uiState.proConfig

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ISO Slider
        ManualSlider(
            label = "ISO",
            value = (config.iso ?: capabilities.isoRange.lower).toFloat(),
            range = capabilities.isoRange.lower.toFloat()..capabilities.isoRange.upper.toFloat(),
            onValueChange = { onConfigChange(config.copy(iso = it.toInt())) }
        )

        // Shutter Speed Slider (Logarithmic or just linear for demo)
        ManualSlider(
            label = "Shutter",
            value = (config.shutterSpeedNs ?: capabilities.shutterSpeedRange.lower).toFloat(),
            range = capabilities.shutterSpeedRange.lower.toFloat()..capabilities.shutterSpeedRange.upper.toFloat(),
            onValueChange = { onConfigChange(config.copy(shutterSpeedNs = it.toLong())) }
        )

        // Focus Slider
        ManualSlider(
            label = "Focus",
            value = config.focusDistance ?: 0.0f,
            range = 0.0f..capabilities.minFocusDistance,
            onValueChange = { onConfigChange(config.copy(focusDistance = it)) }
        )

        // WB Slider
        ManualSlider(
            label = "WB",
            value = (config.whiteBalanceKelvin ?: 5000).toFloat(),
            range = 2000f..10000f,
            onValueChange = { onConfigChange(config.copy(whiteBalanceKelvin = it.toInt())) }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("RAW Support", color = Color.White)
            Switch(
                checked = config.isRawEnabled,
                onCheckedChange = { onConfigChange(config.copy(isRawEnabled = it)) }
            )
        }
    }
}

@Composable
fun ManualSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.Yellow, style = MaterialTheme.typography.labelSmall)
            Text(value.toInt().toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.Yellow,
                activeTrackColor = Color.Yellow,
                inactiveTrackColor = Color.Gray
            )
        )
    }
}
