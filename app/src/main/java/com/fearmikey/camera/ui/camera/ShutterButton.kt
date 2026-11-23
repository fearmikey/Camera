package com.fearmikey.camera.ui.camera

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch

/** Record red used by the video shutter and the recording indicator. */
val RecordRed = Color(0xFFF2382F)

/**
 * SLOW_MOTION / TIME_LAPSE: a smaller white disc with the mode's glyph on a filled dark circle;
 * recording turns it into the red stop square like VIDEO.
 */
enum class ShutterStyle {
    PHOTO, VIDEO, PANORAMA, SLOW_MOTION, TIME_LAPSE;

    val records: Boolean get() = this == VIDEO || this == SLOW_MOTION || this == TIME_LAPSE
    val hasGlyphDisc: Boolean get() = this == SLOW_MOTION || this == TIME_LAPSE
}

/** Outer size of [ShutterButton]; constant in every state so the controls never shift. */
val ShutterSize = 88.dp

private val RingWidth = 7.dp
/** Dark ring around the disc (white on the progress arc and the capture pulse). */
private val RingColor = Color(0xFF3A3A3C)
private val DiscSize = 72.dp
/** White disc of the SLOW_MOTION / TIME_LAPSE styles, on a filled [RingColor] circle. */
private val GlyphDiscSize = 56.dp
private val StopSize = 30.dp
private val PanoramaStopSize = 36.dp
/** Hold time before a press becomes QuickTake / hold-to-record (taps are well under this). */
private const val LONG_PRESS_MS = 300L

/**
 * The capture button. Everything is drawn in one Canvas so the inner shape can morph:
 * photo = white disc in a dark ring, video = red disc in the ring, recording = small red
 * rounded square (stop) in the ring, panorama sweep = dark disc with a white stop square.
 * Pressing shrinks the inner shape (springs back on release); a photo tap also sends a short
 * ring pulse outwards. [progress] (0..1, null = none) turns the ring into a progress arc for
 * multi-frame captures. The footprint is always [ShutterSize], whatever the state.
 *
 * A tap calls [onCapture]; holding for [LONG_PRESS_MS] calls [onLongPressStart] and [onLongPressEnd] on
 * release (QuickTake / hold to record). A null [onLongPressStart] makes a hold act as a tap.
 */
@Composable
fun ShutterButton(
    style: ShutterStyle,
    isRecording: Boolean,
    isPanoramaCapturing: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongPressStart: (() -> Unit)? = null,
    onLongPressEnd: () -> Unit = {},
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestCapture by rememberUpdatedState(onCapture)
    val latestLongStart by rememberUpdatedState(onLongPressStart)
    val latestLongEnd by rememberUpdatedState(onLongPressEnd)
    val latestStyle by rememberUpdatedState(style)
    val latestRecording by rememberUpdatedState(isRecording)

    var pressed by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(1f) }

    val stop = isRecording || isPanoramaCapturing
    val morphSpring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val innerSize by animateDpAsState(
        when {
            isPanoramaCapturing -> PanoramaStopSize
            isRecording -> StopSize
            style.hasGlyphDisc -> GlyphDiscSize
            else -> DiscSize
        },
        morphSpring, label = "shutterInnerSize"
    )
    val squareness by animateFloatAsState(
        if (stop) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "shutterSquareness"
    )
    val innerColor by animateColorAsState(
        when {
            isPanoramaCapturing -> Color.White
            isRecording || style == ShutterStyle.VIDEO -> RecordRed
            else -> Color.White
        },
        tween(240), label = "shutterColor"
    )
    // Panorama sweep: the ring gives way to a translucent disc behind the stop square.
    val ringAlpha by animateFloatAsState(if (isPanoramaCapturing) 0f else 1f, tween(220), label = "shutterRing")
    val pressScale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        if (pressed) spring(stiffness = Spring.StiffnessHigh)
        else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "shutterPress"
    )
    val progressAnim by animateFloatAsState(
        progress ?: 0f, spring(stiffness = Spring.StiffnessLow), label = "shutterProgress"
    )
    val busy = progress != null
    val innerAlpha by animateFloatAsState(if (busy) 0.45f else 1f, tween(200), label = "shutterBusy")
    val glyphAlpha by animateFloatAsState(
        if (style == ShutterStyle.PANORAMA && !isPanoramaCapturing) 1f else 0f, tween(180), label = "panoGlyph"
    )
    // Slow motion / time lapse: filled dark circle behind the disc, mode glyph on the disc.
    val discFill by animateFloatAsState(if (style.hasGlyphDisc) 1f else 0f, tween(200), label = "shutterDiscFill")
    val modeGlyphAlpha by animateFloatAsState(
        if (style.hasGlyphDisc && !isRecording) 1f else 0f, tween(160), label = "modeGlyph"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ShutterSize)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    isPanoramaCapturing -> "Stop panorama"
                    isRecording -> "Stop recording"
                    style.records -> "Start recording"
                    style == ShutterStyle.PANORAMA -> "Start panorama"
                    else -> "Take photo"
                }
                onClick { latestCapture(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var timedOut = true
                    val up = withTimeoutOrNull(LONG_PRESS_MS) {
                        waitForUpOrCancellation().also { timedOut = false }
                    }
                    val longStart = latestLongStart
                    when {
                        timedOut && longStart != null -> {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            longStart()
                            waitForUpOrCancellation()
                            pressed = false
                            latestLongEnd()
                        }
                        timedOut -> {
                            // No hold action: a long tap is still a tap if it ends on the button.
                            val late = waitForUpOrCancellation()
                            pressed = false
                            if (late != null) latestCapture()
                        }
                        up != null -> {
                            pressed = false
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (latestStyle == ShutterStyle.PHOTO && !latestRecording) {
                                scope.launch {
                                    pulse.snapTo(0f)
                                    pulse.animateTo(1f, tween(420, easing = LinearOutSlowInEasing))
                                }
                            }
                            latestCapture()
                        }
                        else -> pressed = false // canceled (e.g. turned into a swipe)
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val ringPx = RingWidth.toPx()
            val ringRadius = (size.minDimension - ringPx) / 2f

            // Capture pulse: a ring that expands past the button and fades out.
            val p = pulse.value
            if (p < 1f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f * (1f - p)),
                    radius = ringRadius + p * 14.dp.toPx(),
                    style = Stroke(width = 4.dp.toPx() * (1f - p * 0.5f))
                )
            }

            if (isPanoramaCapturing || ringAlpha < 1f) {
                drawCircle(Color.White.copy(alpha = 0.16f * (1f - ringAlpha)))
            }
            if (discFill > 0f) {
                drawCircle(RingColor.copy(alpha = discFill * ringAlpha), radius = size.minDimension / 2f)
            }

            if (ringAlpha > 0f) {
                if (busy) {
                    drawCircle(
                        color = RingColor.copy(alpha = ringAlpha),
                        radius = ringRadius, style = Stroke(ringPx)
                    )
                    drawArc(
                        color = Color.White.copy(alpha = ringAlpha),
                        startAngle = -90f,
                        sweepAngle = progressAnim.coerceIn(0f, 1f) * 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(ringPx, cap = StrokeCap.Round)
                    )
                } else {
                    drawCircle(
                        color = RingColor.copy(alpha = ringAlpha),
                        radius = ringRadius, style = Stroke(ringPx)
                    )
                }
            }

            val side = innerSize.toPx() * pressScale
            val corner = lerp(side / 2f, (if (isPanoramaCapturing) 10.dp else 8.dp).toPx(), squareness)
                .coerceIn(0f, side / 2f)
            drawRoundRect(
                color = innerColor.copy(alpha = innerColor.alpha * innerAlpha),
                topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                size = Size(side, side),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
        if (glyphAlpha > 0f) {
            PanoramaShutterGlyph(Modifier.graphicsLayer {
                alpha = glyphAlpha
                scaleX = pressScale
                scaleY = pressScale
            })
        }
        if (modeGlyphAlpha > 0f) {
            Icon(
                if (style == ShutterStyle.TIME_LAPSE) Icons.Default.Timelapse else Icons.Default.SlowMotionVideo,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(28.dp).graphicsLayer {
                    alpha = modeGlyphAlpha
                    scaleX = pressScale
                    scaleY = pressScale
                }
            )
        }
    }
}

/**
 * Recording time with a blinking red dot (pause icon and blinking time while paused).
 * Tabular digits keep the pill from changing width every second.
 */
@Composable
fun RecordingTimerPill(duration: String, isPaused: Boolean, modifier: Modifier = Modifier) {
    val blink = rememberInfiniteTransition(label = "recBlink")
    val blinkAlpha = blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "recBlinkAlpha"
    )
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isPaused) {
                Icon(Icons.Default.Pause, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(14.dp))
            } else {
                Box(
                    Modifier
                        .size(9.dp)
                        .graphicsLayer { alpha = blinkAlpha.value }
                        .background(RecordRed, CircleShape)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = duration,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { alpha = if (isPaused) blinkAlpha.value else 1f }
            )
        }
    }
}

@Preview(widthDp = 520, heightDp = 140, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonPreview() {
    Row(
        Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShutterButton(ShutterStyle.PHOTO, isRecording = false, isPanoramaCapturing = false, onCapture = {})
        ShutterButton(ShutterStyle.VIDEO, isRecording = false, isPanoramaCapturing = false, onCapture = {})
        ShutterButton(ShutterStyle.VIDEO, isRecording = true, isPanoramaCapturing = false, onCapture = {})
        ShutterButton(ShutterStyle.PHOTO, isRecording = false, isPanoramaCapturing = false, onCapture = {}, progress = 0.6f)
        ShutterButton(ShutterStyle.SLOW_MOTION, isRecording = false, isPanoramaCapturing = false, onCapture = {})
    }
}

@Preview(widthDp = 320, heightDp = 140, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonPanoramaPreview() {
    Row(
        Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShutterButton(ShutterStyle.PANORAMA, isRecording = false, isPanoramaCapturing = false, onCapture = {})
        ShutterButton(ShutterStyle.PANORAMA, isRecording = false, isPanoramaCapturing = true, onCapture = {})
        RecordingTimerPill("01:23", isPaused = false)
    }
}
