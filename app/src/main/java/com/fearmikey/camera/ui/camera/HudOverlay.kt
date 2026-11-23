package com.fearmikey.camera.ui.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.domain.model.GridMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

@Composable
fun HudOverlay(
    gridMode: GridMode,
    showLeveler: Boolean,
    levelFlow: StateFlow<LevelState>,
    focusFlow: StateFlow<FocusState?>,
    gestureFlow: StateFlow<GestureOverlayState>,
    isFrontCamera: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Read inside the levelers' draw phase, so ~50 Hz sensor updates don't recompose the HUD.
    val level = levelFlow.collectAsState()
    val isLandscape by remember { derivedStateOf { level.value.isLandscape } }
    val isFacingDown by remember { derivedStateOf { level.value.isFacingDown } }
    val focusPoint by focusFlow.collectAsState()
    val gestureState by gestureFlow.collectAsState()

    Box(modifier = modifier) {
        if (gridMode != GridMode.OFF) {
            CameraGrid(gridMode)
        }
        
        // Like Pixel's framing hints: a horizon line only in landscape, and a bubble level
        // when shooting straight down.
        LevelerFade(visible = showLeveler && isLandscape) {
            HorizonLeveler(roll = { level.value.roll })
        }
        LevelerFade(visible = showLeveler && isFacingDown) {
            TopDownLeveler(tilt = { Offset(level.value.downTiltX, level.value.downTiltY) })
        }
        
        focusPoint?.let { state ->
            FocusRing(state = state)
        }

        gestureState.faceBox?.let { box ->
            FaceDetectionIndicator(box = box, isFrontCamera = isFrontCamera)
        }

        gestureState.handBox?.let { box ->
            HandDetectionBox(box = box, isPending = gestureState.isTimerPending, isFrontCamera = isFrontCamera)
        }
    }
}

@Composable
fun FaceDetectionIndicator(
    box: androidx.compose.ui.geometry.Rect,
    isFrontCamera: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.3f)
        
        // Map normalized coordinates to pixel coordinates
        // Flip X if front camera
        val left = if (isFrontCamera) (1f - box.right) * size.width else box.left * size.width
        val top = box.top * size.height
        val right = if (isFrontCamera) (1f - box.left) * size.width else box.right * size.width
        val bottom = box.bottom * size.height
        
        // Draw a subtle rounded rect around the face
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
fun CameraGrid(mode: GridMode) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        val shadowColor = Color.Black.copy(alpha = 0.2f)

        fun drawGridLine(start: Offset, end: Offset) {
            drawLine(shadowColor, start + Offset(1f, 1f), end + Offset(1f, 1f), strokeWidth)
            drawLine(color, start, end, strokeWidth)
        }

        when (mode) {
            GridMode.GRID_3X3 -> {
                // Vertical
                drawGridLine(Offset(size.width / 3, 0f), Offset(size.width / 3, size.height))
                drawGridLine(Offset(2 * size.width / 3, 0f), Offset(2 * size.width / 3, size.height))
                // Horizontal
                drawGridLine(Offset(0f, size.height / 3), Offset(size.width, size.height / 3))
                drawGridLine(Offset(0f, 2 * size.height / 3), Offset(size.width, 2 * size.height / 3))
            }
            GridMode.GRID_4X4 -> {
                for (i in 1..3) {
                    drawGridLine(Offset(i * size.width / 4, 0f), Offset(i * size.width / 4, size.height))
                    drawGridLine(Offset(0f, i * size.height / 4), Offset(size.width, i * size.height / 4))
                }
            }
            GridMode.GOLDEN_RATIO -> {
                val ratio = 0.618f
                val invRatio = 1f - ratio
                // Vertical
                drawGridLine(Offset(size.width * invRatio, 0f), Offset(size.width * invRatio, size.height))
                drawGridLine(Offset(size.width * ratio, 0f), Offset(size.width * ratio, size.height))
                // Horizontal
                drawGridLine(Offset(0f, size.height * invRatio), Offset(size.width, size.height * invRatio))
                drawGridLine(Offset(0f, size.height * ratio), Offset(size.width, size.height * ratio))
            }
            else -> {}
        }
    }
}

@Composable
private fun LevelerFade(visible: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "levelerFade")
    if (alpha > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) { content() }
    }
}

/** Within this many degrees the levelers snap and turn the accent color. */
private const val LEVEL_TOLERANCE_DEG = 1f

/**
 * Landscape horizon: fixed side marks for the frame and a center line that stays parallel to
 * the real horizon. When level, the line snaps straight and everything turns the accent color.
 * [roll] is the device roll (+-90 in landscape); it is read during drawing only.
 */
@Composable
fun HorizonLeveler(roll: () -> Float) {
    val accent = CameraAccent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val tilt = roll()
        val center = Offset(size.width / 2, size.height / 2)
        val half = 48.dp.toPx()
        val gap = 10.dp.toPx()
        val mark = 24.dp.toPx()

        // Nearest landscape/portrait frame, then the remaining tilt within it.
        val frameRotation = when {
            tilt > -45f && tilt <= 45f -> 0f
            tilt > 45f && tilt <= 135f -> 90f
            tilt > 135f || tilt <= -135f -> 180f
            else -> -90f
        }
        val relative = tilt - frameRotation
        val isLevel = abs(relative) < LEVEL_TOLERANCE_DEG
        val color = if (isLevel) accent else Color.White
        val shadow = Color.Black.copy(alpha = 0.35f)
        val stroke = 2.dp.toPx()
        val shadowOffset = Offset(0f, 1.dp.toPx())

        fun line(start: Offset, end: Offset, c: Color) {
            drawLine(shadow, start + shadowOffset, end + shadowOffset, stroke, cap = StrokeCap.Round)
            drawLine(c, start, end, stroke, cap = StrokeCap.Round)
        }

        rotate(degrees = -frameRotation, pivot = center) {
            // Fixed marks show where "level" is for the photo frame.
            val markColor = if (isLevel) accent else Color.White.copy(alpha = 0.7f)
            line(Offset(center.x - half - gap - mark, center.y), Offset(center.x - half - gap, center.y), markColor)
            line(Offset(center.x + half + gap, center.y), Offset(center.x + half + gap + mark, center.y), markColor)
            // The center line follows the real horizon.
            rotate(degrees = if (isLevel) 0f else -relative, pivot = center) {
                line(Offset(center.x - half, center.y), Offset(center.x + half, center.y), color)
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Levelers", widthDp = 240, heightDp = 880)
@Composable
private fun LevelersPreview() {
    com.fearmikey.camera.ui.theme.ViewfinderTheme {
        val background = Modifier.background(Color(0xFF556B55))
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            // Landscape, 4 degrees off / level.
            Box(Modifier.weight(1f).fillMaxSize().then(background)) { HorizonLeveler(roll = { -86f }) }
            Box(Modifier.weight(1f).fillMaxSize().then(background)) { HorizonLeveler(roll = { -90.4f }) }
            // Pointing down, tilted / level.
            Box(Modifier.weight(1f).fillMaxSize().then(background)) { TopDownLeveler(tilt = { Offset(6f, -3f) }) }
            Box(Modifier.weight(1f).fillMaxSize().then(background)) { TopDownLeveler(tilt = { Offset(0.3f, 0.2f) }) }
        }
    }
}

/**
 * Top-down bubble level: a fixed target circle in the center and a dot that drifts toward the
 * raised edge. Level the phone to bring the dot into the circle; within
 * [LEVEL_TOLERANCE_DEG] it snaps to the center and both turn the accent color.
 * [tilt] is degrees away from straight down (x: right edge raised, y: top edge raised).
 */
@Composable
fun TopDownLeveler(tilt: () -> Offset) {
    val accent = CameraAccent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = tilt()
        val center = Offset(size.width / 2, size.height / 2)
        val isLevel = t.getDistance() < LEVEL_TOLERANCE_DEG
        val color = if (isLevel) accent else Color.White
        val shadow = Color.Black.copy(alpha = 0.35f)
        val ringRadius = 22.dp.toPx()
        val dotRadius = 6.dp.toPx()
        val shadowOffset = Offset(0f, 1.dp.toPx())

        // 4dp per degree, kept inside a 20-degree radius (the leveler hides past 30).
        val perDegree = 4.dp.toPx()
        val maxOffset = 20f * perDegree
        var offset = Offset(t.x * perDegree, -t.y * perDegree)
        val distance = offset.getDistance()
        if (distance > maxOffset) offset *= maxOffset / distance
        if (isLevel) offset = Offset.Zero

        val ringStroke = Stroke(width = 2.dp.toPx())
        drawCircle(shadow, ringRadius, center + shadowOffset, style = ringStroke)
        drawCircle(color, ringRadius, center, style = ringStroke)
        if (isLevel) drawCircle(accent.copy(alpha = 0.25f), ringRadius, center)

        val dotCenter = center + offset
        drawCircle(shadow, dotRadius, dotCenter + shadowOffset)
        drawCircle(color, dotRadius, dotCenter)
    }
}

@Composable
fun FocusRing(state: FocusState) {
    val point = state.point
    val infiniteTransition = rememberInfiniteTransition(label = "focusPulse")
    
    val pulseOpacity by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusPulseOpacity"
    )

    val animatedScale by animateFloatAsState(
        targetValue = 1.0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "focusEntryScale"
    )

    val accent = CameraAccent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radiusPx = 40.dp.toPx()
        val color = if (state.isLocked) accent else Color.White
        val opacity = if (state.isLocked) 1.0f else pulseOpacity
        val animatedColor = color.copy(alpha = opacity)
        
        withTransform({
            translate(left = point.x * size.width, top = point.y * size.height)
            scale(animatedScale, animatedScale, pivot = Offset.Zero)
        }) {
            drawCircle(
                color = animatedColor,
                radius = radiusPx,
                center = Offset.Zero,
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Subtle inner circle
            drawCircle(
                color = animatedColor.copy(alpha = opacity * 0.5f),
                radius = radiusPx * 0.8f,
                center = Offset.Zero,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

@Composable
fun HandDetectionBox(
    box: androidx.compose.ui.geometry.Rect,
    isPending: Boolean = false,
    isFrontCamera: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "boxPulse")
    val cornerPulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPending) 1.4f else 1.2f, // Stronger pulse when pending
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPending) 300 else 500, easing = LinearEasing), // Faster pulse when pending
            repeatMode = RepeatMode.Reverse
        ),
        label = "cornerPulse"
    )

    val accent = CameraAccent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = if (isPending) 3.dp.toPx() else 2.dp.toPx()
        val color = if (isPending) accent else Color.White.copy(alpha = 0.8f) // White until locked on
        
        // Map normalized coordinates to pixel coordinates
        // Flip X if front camera
        val left = if (isFrontCamera) (1f - box.right) * size.width else box.left * size.width
        val top = box.top * size.height
        val right = if (isFrontCamera) (1f - box.left) * size.width else box.right * size.width
        val bottom = box.bottom * size.height
        
        drawRect(
            color = color.copy(alpha = 0.4f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 1.dp.toPx())
        )
        
        // Add small corners for a "tracking" look
        val baseCornerLen = 20.dp.toPx()
        val cornerLen = baseCornerLen * cornerPulse
        
        // Top Left
        drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeWidth * 2)
        drawLine(color, Offset(left, top), Offset(left, top + cornerLen), strokeWidth * 2)
        // Top Right
        drawLine(color, Offset(right, top), Offset(right - cornerLen, top), strokeWidth * 2)
        drawLine(color, Offset(right, top), Offset(right, top + cornerLen), strokeWidth * 2)
        // Bottom Left
        drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth * 2)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeWidth * 2)
        // Bottom Right
        drawLine(color, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeWidth * 2)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeWidth * 2)
    }
}
