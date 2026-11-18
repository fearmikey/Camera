package com.fearmikey.camera.ui.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    tiltFlow: StateFlow<Float>,
    autoShowLevelerFlow: StateFlow<Boolean>,
    focusFlow: StateFlow<FocusState?>,
    gestureFlow: StateFlow<GestureOverlayState>,
    isFrontCamera: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tilt by tiltFlow.collectAsState()
    val autoShowLeveler by autoShowLevelerFlow.collectAsState()
    val focusPoint by focusFlow.collectAsState()
    val gestureState by gestureFlow.collectAsState()

    Box(modifier = modifier) {
        if (gridMode != GridMode.OFF) {
            CameraGrid(gridMode)
        }
        
        if (showLeveler || autoShowLeveler) {
            HorizonLeveler(tilt = tilt)
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
fun HorizonLeveler(tilt: Float) {
    val accent = CameraAccent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val lineLength = 100.dp.toPx()
        
        // Find the closest 90-degree reference point
        val targetRotation = when {
            tilt > -45f && tilt <= 45f -> 0f
            tilt > 45f && tilt <= 135f -> 90f
            tilt > 135f || tilt <= -135f -> 180f
            else -> -90f
        }
        
        val relativeTilt = tilt - targetRotation
        val isLevel = abs(relativeTilt) < 1.0f
        val color = if (isLevel) accent else Color.White
        val strokeWidth = if (isLevel) 2.dp.toPx() else 1.dp.toPx()
        
        withTransform({
            // Rotate the entire leveler to match the device orientation
            rotate(degrees = -targetRotation, pivot = Offset(centerX, centerY))
            // Then rotate the line relative to that for precision
            rotate(degrees = -relativeTilt, pivot = Offset(centerX, centerY))
        }) {
            val shadowColor = Color.Black.copy(alpha = 0.5f)
            
            // Draw shadow for visibility
            drawLine(
                color = shadowColor,
                start = Offset(centerX - lineLength / 2 + 2f, centerY + 2f),
                end = Offset(centerX + lineLength / 2 + 2f, centerY + 2f),
                strokeWidth = strokeWidth
            )

            // Main horizon line
            drawLine(
                color = color,
                start = Offset(centerX - lineLength / 2, centerY),
                end = Offset(centerX + lineLength / 2, centerY),
                strokeWidth = strokeWidth
            )
            
            // Subtle "notches" at ends
            drawLine(color, Offset(centerX - lineLength / 2, centerY - 10f), Offset(centerX - lineLength / 2, centerY + 10f), strokeWidth)
            drawLine(color, Offset(centerX + lineLength / 2, centerY - 10f), Offset(centerX + lineLength / 2, centerY + 10f), strokeWidth)
        }
        
        // Static center reference
        drawCircle(Color.White.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(centerX, centerY))
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
