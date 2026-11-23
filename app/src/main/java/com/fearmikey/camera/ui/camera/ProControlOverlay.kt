package com.fearmikey.camera.ui.camera

import android.util.Range
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material.icons.filled.WbIridescent
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.domain.model.CameraHardwareCapabilities
import com.fearmikey.camera.domain.model.ProConfig
import com.fearmikey.camera.domain.model.ModeCapabilities
import com.fearmikey.camera.domain.model.SlowMotion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Manual controls, laid out like a pro camera app: a tool row replaces the mode row and the
 * bottom buttons (ProToolBar), the selected tool's dial floats where the zoom row sits
 * (ProToolControl) and a strip under the top bar summarizes what is auto/manual (ProInfoStrip).
 */

/** One manual control. Which ones show depends on the camera (see [availableProTools]). */
enum class ProTool(val label: String, val icon: ImageVector) {
    SPEED("Speed", Icons.Default.SlowMotionVideo),
    EXPOSURE("Exposure", Icons.Default.Brightness6),
    ISO("ISO", Icons.Default.Iso),
    SHUTTER("Shutter speed", Icons.Default.ShutterSpeed),
    FOCUS("Focus", Icons.Default.CenterFocusWeak),
    WHITE_BALANCE("White balance", Icons.Default.Thermostat),
}

/**
 * Tools offered for this camera and mode, in tool-row order: Speed in Slow motion (when the
 * camera has high-speed rates), exposure compensation wherever the row opens, and ISO /
 * shutter / focus / white balance only where the capture path honors manual settings
 * (ModeCapabilities.supportsManualControls; not in Slow motion / Time lapse).
 */
fun availableProTools(
    capabilities: CameraHardwareCapabilities?,
    modeCaps: ModeCapabilities,
    slowMotionRates: List<Int> = emptyList(),
): List<ProTool> {
    if (!modeCaps.offersAdjustments) return emptyList()
    return buildList {
        if (modeCaps.supportsSlowMotionSpeed && slowMotionRates.isNotEmpty()) add(ProTool.SPEED)
        if (capabilities == null) return@buildList
        if (capabilities.exposureRange.lower < capabilities.exposureRange.upper) add(ProTool.EXPOSURE)
        if (!modeCaps.supportsManualControls) return@buildList
        if (capabilities.supportsManualSensor) {
            add(ProTool.ISO)
            add(ProTool.SHUTTER)
        }
        if (capabilities.minFocusDistance > 0f) add(ProTool.FOCUS)
        add(ProTool.WHITE_BALANCE)
    }
}

/** The tool is set away from its automatic value. */
private fun ProTool.isManual(config: ProConfig, exposureIndex: Int): Boolean = when (this) {
    ProTool.SPEED -> false
    ProTool.EXPOSURE -> exposureIndex != 0
    ProTool.ISO -> config.iso != null
    ProTool.SHUTTER -> config.shutterSpeedNs != null
    ProTool.FOCUS -> config.focusDistance != null
    ProTool.WHITE_BALANCE -> config.whiteBalanceKelvin != null
}

/** White balance presets (Kelvin, mapped to CONTROL_AWB_MODE presets by the engines). */
private val WhiteBalancePresets: List<Triple<Int?, String, ImageVector>> = listOf(
    Triple(null, "Auto", Icons.Default.WbAuto),
    Triple(2850, "Tungsten", Icons.Default.WbIncandescent),
    Triple(4000, "Fluorescent", Icons.Default.WbIridescent),
    Triple(5500, "Daylight", Icons.Default.WbSunny),
    Triple(6500, "Cloudy", Icons.Default.WbCloudy),
)

fun formatShutter(ns: Double): String {
    val seconds = ns / 1e9
    return if (seconds >= 0.3) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()}s"
    }
}

private fun formatFocus(diopters: Float): String =
    if (diopters < 0.05f) "∞" else String.format(Locale.US, "%.2fm", 1f / diopters)

private fun formatEv(index: Int, step: Float): String =
    if (index == 0) "0" else String.format(Locale.US, "%+.1f", index * step).replace(".0", "")

/**
 * Tool row shown instead of the mode row and the bottom buttons while manual controls are
 * open: Reset All, a divider, then one button per tool (the selected one is an accent circle,
 * tools set away from auto get a small accent dot). Tapping or dragging the handle down closes it.
 */
@Composable
fun ProToolBar(
    tools: List<ProTool>,
    selected: ProTool?,
    config: ProConfig,
    exposureIndex: Int,
    onSelect: (ProTool) -> Unit,
    onResetAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = "Close manual controls" }
                .clickable(onClick = onClose)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > 24.dp.toPx()) onClose() },
                    ) { change, dy ->
                        change.consume()
                        total += dy
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(32.dp, 4.dp).background(Color.White.copy(alpha = 0.4f), CircleShape))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            ToolButton(
                icon = Icons.Default.RestartAlt,
                label = "Reset All",
                selected = false,
                modified = false,
                labelColor = Color.White.copy(alpha = 0.7f),
                onClick = onResetAll,
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .padding(top = 12.dp)
                    .size(2.dp, 32.dp)
                    .background(Color.White.copy(alpha = 0.3f), CircleShape)
            )
            tools.forEach { tool ->
                ToolButton(
                    icon = tool.icon,
                    label = tool.label,
                    selected = tool == selected,
                    modified = tool.isManual(config, exposureIndex),
                    onClick = { onSelect(tool) },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
    labelColor: Color = Color.White,
) {
    // Rounded square that turns into an accent circle when selected.
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, tween(200), label = "toolCorner")
    val container by animateColorAsState(if (selected) CameraAccent else ControlBackground, tween(200), label = "toolBg")
    val content by animateColorAsState(if (selected) OnCameraAccent else Color.White, tween(200), label = "toolFg")
    Column(
        Modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(corner))
                .background(container)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
            if (modified && !selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(7.dp)
                        .background(CameraAccent, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = labelColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The selected tool's control, floating above the shutter: a tick ruler with a value bubble
 * (exposure, ISO, shutter, focus) or a preset pill row (white balance).
 */
@Composable
fun ProToolControl(
    tool: ProTool,
    capabilities: CameraHardwareCapabilities,
    config: ProConfig,
    exposureIndex: Int,
    onConfigChange: (ProConfig) -> Unit,
    onExposureIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Slow-motion capture rates (CameraUiState.slowMotionRates) for the Speed tool. */
    slowMotionRates: List<Int> = emptyList(),
    slowMotionFps: Int = SlowMotion.DEFAULT_FPS,
    onSlowMotionFpsChange: (Int) -> Unit = {},
) {
    when (tool) {
        ProTool.SPEED -> SlowMotionSpeedControl(
            rates = slowMotionRates,
            selectedFps = slowMotionFps,
            onSelect = onSlowMotionFpsChange,
            modifier = modifier,
        )
        ProTool.EXPOSURE -> {
            val lo = capabilities.exposureRange.lower
            val hi = capabilities.exposureRange.upper
            val step = capabilities.exposureStep.takeIf { it > 0f } ?: 1f
            val ticks = hi - lo + 1
            val perEv = (1f / step).roundToInt().coerceAtLeast(1)
            ProDial(
                valueText = formatEv(exposureIndex, step),
                position = (exposureIndex - lo).toFloat() / (ticks - 1),
                tickCount = ticks,
                isMajor = { (lo + it) % perEv == 0 },
                tickLabel = { i -> formatEv(lo + i, step).takeIf { (lo + i) % perEv == 0 } },
                snapToTicks = true,
                onPosition = { f -> onExposureIndexChange(lo + (f * (ticks - 1)).roundToInt()) },
                leading = { DialIcon(Icons.Default.BrightnessLow) },
                trailing = { DialIcon(Icons.Default.BrightnessHigh) },
                modifier = modifier,
            )
        }
        ProTool.ISO -> LogDial(
            value = config.iso?.toDouble(),
            range = capabilities.isoRange.let { Range(it.lower.toDouble(), it.upper.toDouble()) },
            autoText = "Auto ISO",
            format = { "ISO ${it.roundToInt()}" },
            onValue = { onConfigChange(config.copy(iso = it?.roundToInt())) },
            trailingIcon = Icons.Default.Iso,
            modifier = modifier,
        )
        ProTool.SHUTTER -> LogDial(
            value = config.shutterSpeedNs?.toDouble(),
            range = capabilities.shutterSpeedRange.let { Range(it.lower.toDouble(), it.upper.toDouble()) },
            autoText = "Auto shutter",
            format = ::formatShutter,
            onValue = { onConfigChange(config.copy(shutterSpeedNs = it?.toLong())) },
            trailingIcon = Icons.Default.ShutterSpeed,
            modifier = modifier,
        )
        ProTool.FOCUS -> {
            val max = capabilities.minFocusDistance
            val ticks = 41
            val current = config.focusDistance
            ProDial(
                valueText = current?.let(::formatFocus) ?: "Auto focus",
                position = ((current ?: 0f) / max).coerceIn(0f, 1f),
                tickCount = ticks,
                isMajor = { it % 10 == 0 },
                tickLabel = { null },
                snapToTicks = false,
                onPosition = { f -> onConfigChange(config.copy(focusDistance = f * max)) },
                leading = { AutoButton(selected = current == null) { onConfigChange(config.copy(focusDistance = null)) } },
                trailing = { DialIcon(Icons.Default.LocalFlorist) },
                dimmed = current == null,
                startIcon = Icons.Default.Landscape,
                modifier = modifier,
            )
        }
        ProTool.WHITE_BALANCE -> WhiteBalancePill(
            kelvin = config.whiteBalanceKelvin,
            onKelvin = { onConfigChange(config.copy(whiteBalanceKelvin = it)) },
            modifier = modifier,
        )
    }
}

/** Logarithmic dial (ISO, shutter): equal ruler distance for equal ratios. Null = auto. */
@Composable
private fun LogDial(
    value: Double?,
    range: Range<Double>,
    autoText: String,
    format: (Double) -> String,
    onValue: (Double?) -> Unit,
    trailingIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val lo = ln(range.lower.coerceAtLeast(1e-9))
    val hi = ln(range.upper.coerceAtLeast(range.lower + 1e-9))
    val ticks = 41
    val valueAt = { f: Float -> exp(lo + f * (hi - lo)) }
    // Auto rests in the middle of the ruler; the first drag starts from there.
    val position = value?.let { ((ln(it) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) } ?: 0.5f
    ProDial(
        valueText = value?.let(format) ?: autoText,
        position = position,
        tickCount = ticks,
        isMajor = { it % 10 == 0 },
        // Evenly spaced log ticks land on odd values ("1/316s"); the bubble shows the value.
        tickLabel = { null },
        snapToTicks = false,
        onPosition = { f -> onValue(valueAt(f)) },
        leading = { AutoButton(selected = value == null) { onValue(null) } },
        trailing = { DialIcon(trailingIcon) },
        dimmed = value == null,
        modifier = modifier,
    )
}

/**
 * Value bubble over a dark pill holding [leading], the ruler and [trailing]. The ruler slides
 * under a fixed center mark: drag left to increase.
 */
@Composable
private fun ProDial(
    valueText: String,
    /** 0..1 along the ruler. */
    position: Float,
    tickCount: Int,
    isMajor: (Int) -> Boolean,
    tickLabel: (Int) -> String?,
    snapToTicks: Boolean,
    onPosition: (Float) -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Auto: the ruler is drawn faded until the user drags it. */
    dimmed: Boolean = false,
    /** Optional glyph drawn at the ruler's start (focus: the far end). */
    startIcon: ImageVector? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            valueText,
            color = OnCameraHighlight,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier
                .background(CameraHighlight, CircleShape)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(52.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DialRuler(
                    position = position,
                    tickCount = tickCount,
                    isMajor = isMajor,
                    tickLabel = tickLabel,
                    snapToTicks = snapToTicks,
                    onPosition = onPosition,
                    dimmed = dimmed,
                )
                if (startIcon != null) {
                    Icon(
                        startIcon, null, tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterStart).size(16.dp)
                    )
                }
            }
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

@Composable
private fun DialRuler(
    position: Float,
    tickCount: Int,
    isMajor: (Int) -> Boolean,
    tickLabel: (Int) -> String?,
    snapToTicks: Boolean,
    onPosition: (Float) -> Unit,
    dimmed: Boolean,
) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer()
    val accent = CameraAccent
    val latestPosition by rememberUpdatedState(position)
    val latestOnPosition by rememberUpdatedState(onPosition)
    val spacing = 9.dp
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(tickCount, snapToTicks) {
                val span = (tickCount - 1).coerceAtLeast(1)
                val spacingPx = spacing.toPx()
                var dragPos = 0f
                var lastTick = -1
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragPos = latestPosition
                        lastTick = (dragPos * span).roundToInt()
                    },
                ) { change, dx ->
                    change.consume()
                    dragPos = (dragPos - dx / spacingPx / span).coerceIn(0f, 1f)
                    val tick = (dragPos * span).roundToInt()
                    if (tick != lastTick) {
                        lastTick = tick
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    latestOnPosition(if (snapToTicks) tick / span.toFloat() else dragPos)
                }
            }
    ) {
        val span = (tickCount - 1).coerceAtLeast(1)
        val spacingPx = spacing.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f - 5.dp.toPx()
        val baseAlpha = if (dimmed) 0.45f else 1f
        val current = position * span
        for (i in 0 until tickCount) {
            val x = cx + (i - current) * spacingPx
            if (x < 0f || x > size.width) continue
            // Ticks fade towards the pill's ends.
            val edge = 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f)
            val alpha = baseAlpha * (0.15f + 0.85f * edge)
            val major = isMajor(i)
            val half = (if (major) 8.dp else 5.dp).toPx()
            drawLine(
                Color.White.copy(alpha = alpha),
                Offset(x, cy - half), Offset(x, cy + half),
                strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(),
                cap = StrokeCap.Round,
            )
            val label = tickLabel(i)
            if (label != null) {
                val layout = measurer.measure(label, labelStyle)
                drawText(
                    layout,
                    color = (if (abs(x - cx) < spacingPx / 2) accent else Color.White).copy(alpha = alpha * 0.8f),
                    topLeft = Offset(x - layout.size.width / 2f, cy + 10.dp.toPx()),
                )
            }
        }
        // Fixed center mark.
        val markH = 12.dp.toPx()
        drawRoundRect(
            accent,
            topLeft = Offset(cx - 2.dp.toPx(), cy - markH),
            size = Size(4.dp.toPx(), markH * 2),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
private fun DialIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
}

/** Circled "A": returns the control to automatic. */
@Composable
private fun AutoButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (selected) CameraAccent else Color.Transparent)
            .border(1.5.dp, if (selected) CameraAccent else Color.White.copy(alpha = 0.8f), CircleShape)
            .semantics { contentDescription = "Auto" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "A",
            color = if (selected) OnCameraAccent else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Segmented preset row; the selected preset shows its name next to the icon. */
@Composable
private fun WhiteBalancePill(kelvin: Int?, onKelvin: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(52.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteBalancePresets.forEach { (k, name, icon) ->
            val selected = k == kelvin
            val bg by animateColorAsState(if (selected) CameraHighlight else Color.Transparent, tween(180), label = "wbBg")
            val fg by animateColorAsState(if (selected) OnCameraHighlight else Color.White, tween(180), label = "wbFg")
            Row(
                Modifier
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(bg)
                    .semantics { contentDescription = "White balance $name" }
                    .clickable { onKelvin(k) }
                    .padding(horizontal = if (selected) 14.dp else 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(name, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Translucent strip at the top of the viewfinder: the state of each manual control ("Ⓐ ISO"
 * while automatic, the value once set). Tapping an item opens its dial.
 */
@Composable
fun ProInfoStrip(
    tools: List<ProTool>,
    config: ProConfig,
    onSelect: (ProTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = tools.filter { it != ProTool.EXPOSURE && it != ProTool.SPEED }
    if (items.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { tool ->
            val manualText: String? = when (tool) {
                ProTool.ISO -> config.iso?.let { "ISO $it" }
                ProTool.SHUTTER -> config.shutterSpeedNs?.let { formatShutter(it.toDouble()) }
                ProTool.FOCUS -> config.focusDistance?.let(::formatFocus)
                ProTool.WHITE_BALANCE -> config.whiteBalanceKelvin?.let { k ->
                    WhiteBalancePresets.firstOrNull { it.first == k }?.second ?: "${k}K"
                }
                ProTool.EXPOSURE, ProTool.SPEED -> null
            }
            val autoText = when (tool) {
                ProTool.ISO -> "ISO"
                ProTool.SHUTTER -> "Shutter"
                ProTool.FOCUS -> "Auto Focus"
                ProTool.WHITE_BALANCE -> "WB"
                ProTool.EXPOSURE, ProTool.SPEED -> ""
            }
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable { onSelect(tool) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (manualText == null && tool != ProTool.FOCUS) {
                    Box(
                        Modifier.size(17.dp).border(1.2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "A",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.Bold,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            ),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    manualText ?: autoText,
                    color = if (manualText != null) CameraAccent else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

private val PreviewCapabilities = CameraHardwareCapabilities(
    isoRange = Range(50, 6400),
    shutterSpeedRange = Range(10_000L, 1_000_000_000L),
    minFocusDistance = 10f,
    exposureRange = Range(-12, 12),
    exposureStep = 1f / 6f,
    supportsManualSensor = true,
)

@Preview(widthDp = 412, heightDp = 470, showBackground = true, backgroundColor = 0xFF5A5040)
@Composable
private fun ProControlsPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme {
        val config = ProConfig(shutterSpeedNs = 8_000_000L, whiteBalanceKelvin = 5500)
        val photo = ModeCapabilities.forMode(com.fearmikey.camera.domain.model.CameraMode.PHOTO, false, com.fearmikey.camera.domain.model.UserPreferences())
        val tools = availableProTools(PreviewCapabilities, photo)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProInfoStrip(tools, config, onSelect = {})
            ProToolControl(ProTool.EXPOSURE, PreviewCapabilities, config, 0, {}, {}, Modifier.fillMaxWidth())
            ProToolControl(ProTool.SHUTTER, PreviewCapabilities, config, 0, {}, {}, Modifier.fillMaxWidth())
            ProToolControl(ProTool.WHITE_BALANCE, PreviewCapabilities, config, 0, {}, {}, Modifier.align(Alignment.CenterHorizontally))
            Box(Modifier.background(Color.Black)) {
                ProToolBar(
                    tools = tools, selected = ProTool.EXPOSURE,
                    config = config, exposureIndex = 0, onSelect = {}, onResetAll = {}, onClose = {},
                )
            }
        }
    }
}

/** Slow motion: the Speed tool's control above the shutter and a row of Speed + Exposure. */
@Preview(widthDp = 412, heightDp = 260, showBackground = true, backgroundColor = 0xFF4A4A4A)
@Composable
private fun SlowMotionToolsPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme {
        val slowMo = ModeCapabilities.forMode(com.fearmikey.camera.domain.model.CameraMode.SLOW_MO, false, com.fearmikey.camera.domain.model.UserPreferences())
        val tools = availableProTools(PreviewCapabilities, slowMo, slowMotionRates = listOf(120, 240))
        Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            ProToolControl(
                ProTool.SPEED, PreviewCapabilities, ProConfig(), 0, {}, {},
                slowMotionRates = listOf(120, 240), slowMotionFps = 120,
            )
            Box(Modifier.background(Color.Black)) {
                ProToolBar(
                    tools = tools, selected = ProTool.SPEED,
                    config = ProConfig(), exposureIndex = 0, onSelect = {}, onResetAll = {}, onClose = {},
                )
            }
        }
    }
}
