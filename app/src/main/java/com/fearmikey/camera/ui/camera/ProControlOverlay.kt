package com.fearmikey.camera.ui.camera

import android.util.Range
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.ProConfig
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Compact manual-controls panel. Each control has an "Auto" chip; moving a
 * slider switches that control to manual. Shutter and ISO use logarithmic
 * sliders so the useful range isn't squeezed into a few pixels.
 */
@Composable
fun ProControlOverlay(
    uiState: CameraUiState,
    onConfigChange: (ProConfig) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capabilities = uiState.capabilities ?: return
    ProControlPanel(capabilities, uiState.proConfig, onConfigChange, onReset, modifier)
}

@Composable
fun ProControlPanel(
    capabilities: CameraHardwareCapabilities,
    config: ProConfig,
    onConfigChange: (ProConfig) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Manual", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!capabilities.supportsManualSensor) {
                Text("ISO/shutter unsupported", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
            TextButton(onClick = onReset) { Text("Reset", color = CameraAccent) }
        }

        if (capabilities.supportsManualSensor) {
            LogSliderRow(
                label = "ISO",
                value = config.iso?.toDouble(),
                range = capabilities.isoRange.toDoubleRange(),
                format = { it.roundToInt().toString() },
                onValue = { onConfigChange(config.copy(iso = it?.roundToInt())) }
            )
            LogSliderRow(
                label = "Shutter",
                value = config.shutterSpeedNs?.toDouble(),
                range = Range(
                    capabilities.shutterSpeedRange.lower.toDouble(),
                    capabilities.shutterSpeedRange.upper.toDouble()
                ),
                format = ::formatShutter,
                onValue = { onConfigChange(config.copy(shutterSpeedNs = it?.toLong())) }
            )
        }

        if (capabilities.minFocusDistance > 0f) {
            LinearSliderRow(
                label = "Focus",
                value = config.focusDistance,
                range = 0f..capabilities.minFocusDistance,
                format = { d -> if (d < 0.05f) "∞" else String.format(Locale.US, "%.2fm", 1f / d) },
                onValue = { onConfigChange(config.copy(focusDistance = it)) }
            )
        }

        WhiteBalanceRow(
            kelvin = config.whiteBalanceKelvin,
            onKelvin = { onConfigChange(config.copy(whiteBalanceKelvin = it)) }
        )
    }
}

private fun Range<Int>.toDoubleRange() = Range(lower.toDouble(), upper.toDouble())

fun formatShutter(ns: Double): String {
    val seconds = ns / 1e9
    return if (seconds >= 0.3) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()}"
    }
}

@Composable
private fun ControlLabel(label: String, valueText: String, isAuto: Boolean, onAuto: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Text(
            if (isAuto) "Auto" else valueText,
            color = if (isAuto) Color.Gray else CameraAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        AutoChip(selected = isAuto, onClick = onAuto)
    }
}

@Composable
private fun AutoChip(selected: Boolean, onClick: () -> Unit) {
    Text(
        "A",
        color = if (selected) OnCameraAccent else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) CameraAccent else Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .wrapContentSize(Alignment.Center)
    )
}

@Composable
private fun LogSliderRow(
    label: String,
    value: Double?,
    range: Range<Double>,
    format: (Double) -> String,
    onValue: (Double?) -> Unit,
) {
    val lo = ln(range.lower.coerceAtLeast(1e-9))
    val hi = ln(range.upper.coerceAtLeast(range.lower + 1e-9))
    val current = value ?: exp((lo + hi) / 2)
    Column {
        ControlLabel(label, format(current), value == null) { onValue(null) }
        Slider(
            value = ((ln(current) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f),
            onValueChange = { f -> onValue(exp(lo + f * (hi - lo))) },
            modifier = Modifier.height(28.dp),
            colors = sliderColors(),
        )
    }
}

@Composable
private fun LinearSliderRow(
    label: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValue: (Float?) -> Unit,
) {
    val current = value ?: range.start
    Column {
        ControlLabel(label, format(current), value == null) { onValue(null) }
        Slider(
            value = current,
            onValueChange = { onValue(it) },
            valueRange = range,
            modifier = Modifier.height(28.dp),
            colors = sliderColors(),
        )
    }
}

@Composable
private fun WhiteBalanceRow(kelvin: Int?, onKelvin: (Int?) -> Unit) {
    val presets = listOf(null to "Auto", 2850 to "Tungsten", 4000 to "Fluor.", 5500 to "Sun", 6500 to "Cloud")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("WB", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(32.dp))
        presets.forEach { (k, name) ->
            val selected = k == kelvin
            Text(
                name,
                color = if (selected) OnCameraAccent else Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) CameraAccent else Color.White.copy(alpha = 0.12f))
                    .clickable { onKelvin(k) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = CameraAccent,
    activeTrackColor = CameraAccent,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
)

@Preview(widthDp = 380, showBackground = true, backgroundColor = 0xFF555555)
@Composable
private fun ProControlPanelPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme {
        ProControlPanel(
            capabilities = CameraHardwareCapabilities(
                isoRange = Range(50, 6400),
                shutterSpeedRange = Range(10_000L, 1_000_000_000L),
                minFocusDistance = 10f,
                supportsManualSensor = true,
            ),
            config = ProConfig(shutterSpeedNs = 8_000_000L, whiteBalanceKelvin = 5500),
            onConfigChange = {},
            onReset = {},
        )
    }
}
