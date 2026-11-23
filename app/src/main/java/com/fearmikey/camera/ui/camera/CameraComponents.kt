package com.fearmikey.camera.ui.camera

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fearmikey.camera.domain.model.CodeFormat
import com.fearmikey.camera.domain.model.ScannedCode
import java.util.Locale
import kotlin.math.roundToInt

/** Height reserved for [CameraTopBar]; the viewfinder starts directly below it. */
val TopBarHeight = 44.dp

/** Accent for active controls: the system (Material You) primary color. */
val CameraAccent: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

/** Content drawn on top of [CameraAccent]. */
val OnCameraAccent: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary

/** Round control buttons on the dark camera UI (tinted by the system palette). */
val ControlBackground: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

/** Icon button whose glyph rotates with the device so it stays upright in landscape. */
@Composable
fun RotatingIconButton(
    icon: ImageVector,
    contentDescription: String,
    rotation: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    background: Color = Color.Unspecified,
) {
    val bg = if (background == Color.Unspecified) ControlBackground else background
    val animated by animateFloatAsState(
        targetValue = rotation.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconRotation"
    )
    IconButton(onClick = onClick, modifier = modifier.background(bg, CircleShape)) {
        // Rotation is read in the draw phase, so the turn animation doesn't recompose the button.
        Icon(
            icon, contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = animated },
        )
    }
}

/**
 * Vertical brightness (exposure compensation) slider shown next to the focus
 * ring. Drag up to brighten. [index] and [range] are in device EV steps.
 */
@Composable
fun ExposureSlider(
    index: Int,
    range: IntRange,
    stepEv: Float,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (range.first >= range.last) return
    val trackHeight = 140.dp
    val steps = (range.last - range.first).toFloat()
    // Drag position is tracked in "steps" so slow drags still register.
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val currentIndex by rememberUpdatedState(index)

    Column(
        modifier = modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val ev = index * stepEv
        Text(
            text = if (index == 0) "0" else String.format(Locale.US, "%+.1f", ev),
            color = if (index == 0) Color.White else CameraAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(44.dp)
                .pointerInput(range) {
                    val pxPerStep = size.height / steps
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccumulator -= dragAmount / pxPerStep
                        val delta = dragAccumulator.toInt()
                        if (delta != 0) {
                            dragAccumulator -= delta
                            onIndexChange((currentIndex + delta).coerceIn(range.first, range.last))
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width / 2
                drawLine(Color.White.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                // Zero tick
                val zeroY = size.height * (range.last - 0) / steps
                drawLine(Color.White, Offset(x - 6.dp.toPx(), zeroY), Offset(x + 6.dp.toPx(), zeroY), 2.dp.toPx())
            }
            val fraction = (range.last - index) / steps
            Icon(
                Icons.Default.WbSunny,
                contentDescription = "Brightness",
                tint = if (index == 0) Color.White else CameraAccent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = trackHeight * fraction - 12.dp)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(2.dp)
            )
        }
    }
}

/** Shown when the camera permission has been denied. */
@Composable
fun PermissionRequiredScreen(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text("Camera access needed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "This app needs the camera to take photos and videos. Everything stays on your device.",
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Open app settings", color = Color.White) }
    }
}

/**
 * Top bar: resolution chip, flash toggle, timer and macro indicators.
 * (Camera switching lives next to the shutter.) Glyphs rotate with [rotation].
 */
@Composable
fun CameraTopBar(
    rotation: Int,
    showResolution: Boolean,
    isFullResolution: Boolean,
    showFlash: Boolean,
    flashLabel: String,
    flashIcon: ImageVector,
    flashActive: Boolean,
    timerSeconds: Int,
    macroOn: Boolean,
    onResolutionClick: () -> Unit,
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null shows the "scan a code from an image" button. */
    onScanImageClick: (() -> Unit)? = null,
    isScanningImage: Boolean = false,
    /**
     * Resolution/timer/macro chips react to taps. Off while recording: they change settings
     * that rebind the camera, which would end the recording. (Flash stays: in video it is the torch.)
     */
    chipsEnabled: Boolean = true,
) {
    val animatedRotation by animateFloatAsState(
        targetValue = rotation.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "topBarRotation"
    )
    // Read only in the draw phase (graphicsLayer), so turning the phone doesn't recompose the
    // bar on every animation frame.
    val rotationProvider = { animatedRotation }
    val rotate = Modifier.graphicsLayer { rotationZ = animatedRotation }
    Row(
        modifier = modifier.fillMaxWidth().height(TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (showResolution) {
            TopBarChip(
                text = if (isFullResolution) "MAX" else "12MP",
                active = isFullResolution,
                rotation = rotationProvider,
                onClick = onResolutionClick,
                description = if (isFullResolution) "Full resolution" else "12 megapixel",
                enabled = chipsEnabled,
            )
        }
        if (showFlash) {
            IconButton(onClick = onFlashClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    flashIcon,
                    contentDescription = "Flash $flashLabel",
                    tint = if (flashActive) CameraAccent else Color.White,
                    modifier = Modifier.size(22.dp).then(rotate)
                )
            }
        }
        if (timerSeconds > 0) {
            TopBarChip(
                text = "${timerSeconds}s",
                active = true,
                rotation = rotationProvider,
                onClick = onTimerClick,
                description = "Timer $timerSeconds seconds",
                enabled = chipsEnabled,
            )
        }
        if (macroOn) {
            // Opens the quick settings sheet (macro is set there), like the timer chip.
            TopBarChip(
                text = "MACRO", active = true, rotation = rotationProvider, onClick = onTimerClick,
                description = "Macro focus on", enabled = chipsEnabled,
            )
        }
        if (onScanImageClick != null) {
            IconButton(onClick = onScanImageClick, enabled = !isScanningImage, modifier = Modifier.size(40.dp)) {
                if (isScanningImage) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "Scan code from image",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).then(rotate)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarChip(
    text: String,
    active: Boolean,
    /** Degrees, read in the draw phase. */
    rotation: () -> Float,
    onClick: () -> Unit,
    description: String,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (active) CameraAccent else Color.Black.copy(alpha = 0.35f))
            .clickableWithDescription(description, enabled, onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) OnCameraAccent else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { rotationZ = rotation() }
        )
    }
}

private fun Modifier.clickableWithDescription(description: String, enabled: Boolean, onClick: () -> Unit): Modifier =
    this
        .semantics { contentDescription = description; role = Role.Button }
        .clickable(enabled = enabled, onClick = onClick)

/** Contextual chips under the top bar: detected QR codes/barcodes and low-light Night suggestion. */
@Composable
fun SuggestionChips(
    codes: List<ScannedCode>,
    showNightSuggestion: Boolean,
    onCodesClick: () -> Unit,
    onCodesDismiss: () -> Unit,
    onNightClick: () -> Unit,
    onNightDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        codes.firstOrNull()?.let { first ->
            val payload = first.payload
            if (codes.size == 1) {
                SuggestionChip(
                    icon = CodeActions.icon(payload),
                    title = CodeActions.primaryLabel(payload),
                    subtitle = CodeActions.summary(payload).ifEmpty { CodeActions.title(payload) }.take(48),
                    onClick = onCodesClick,
                    onDismiss = onCodesDismiss,
                )
            } else {
                SuggestionChip(
                    icon = Icons.Default.QrCode2,
                    title = "${codes.size} codes found",
                    subtitle = codes.joinToString(" · ") { CodeActions.title(it.payload) }.take(48),
                    onClick = onCodesClick,
                    onDismiss = onCodesDismiss,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (showNightSuggestion) {
            SuggestionChip(
                icon = Icons.Default.NightsStay,
                title = "It's dark here",
                subtitle = "Tap to use Night mode",
                onClick = onNightClick,
                onDismiss = onNightDismiss,
            )
        }
    }
}

@Composable
private fun SuggestionChip(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CameraAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 240.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Zoom label: ".6", "1x", "2.5x". */
fun formatZoom(zoom: Float): String {
    val rounded = (zoom * 10f).roundToInt() / 10f
    return when {
        rounded < 1f -> rounded.toString().removePrefix("0")
        rounded == rounded.toInt().toFloat() -> "${rounded.toInt()}x"
        else -> "${rounded}x"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF444444)
@Composable
private fun ExposureSliderPreview() {
    ExposureSlider(index = 3, range = -12..12, stepEv = 1f / 6f, onIndexChange = {})
}

@Preview(widthDp = 400, showBackground = true, backgroundColor = 0xFF3A4A3A)
@Composable
private fun CameraTopBarPreview() {
    CameraTopBar(
        rotation = 0, showResolution = true, isFullResolution = false, showFlash = true,
        flashLabel = "Auto", flashIcon = Icons.Default.FlashAuto, flashActive = false,
        timerSeconds = 3, macroOn = true,
        onResolutionClick = {}, onFlashClick = {}, onTimerClick = {},
        modifier = Modifier.padding(horizontal = 12.dp),
        onScanImageClick = {},
    )
}

@Preview(widthDp = 400, showBackground = true, backgroundColor = 0xFF3A4A3A)
@Composable
private fun SuggestionChipsPreview() {
    Column {
        SuggestionChips(
            codes = listOf(ScannedCode("https://f-droid.org", CodeFormat.QR_CODE)), showNightSuggestion = true,
            onCodesClick = {}, onCodesDismiss = {}, onNightClick = {}, onNightDismiss = {}
        )
        SuggestionChips(
            codes = listOf(
                ScannedCode("WIFI:T:WPA;S:Cafe;P:espresso42;;", CodeFormat.QR_CODE),
                ScannedCode("4006381333931", CodeFormat.EAN_13),
            ),
            showNightSuggestion = false,
            onCodesClick = {}, onCodesDismiss = {}, onNightClick = {}, onNightDismiss = {}
        )
    }
}

@Preview
@Composable
private fun PermissionScreenPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme { PermissionRequiredScreen(onRequest = {}) }
}
