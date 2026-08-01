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
import kotlin.math.abs

@Composable
fun HudOverlay(
    uiState: CameraUiState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (uiState.userPreferences.showGridLines) {
            RuleOfThirdsGrid()
        }
        
        if (uiState.userPreferences.showLeveler || uiState.autoShowLeveler) {
            HorizonLeveler(tilt = uiState.horizonTilt)
        }
        
        uiState.focusPoint?.let { point ->
            FocusRing(point = point)
        }
    }
}

@Composable
fun RuleOfThirdsGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        val shadowColor = Color.Black.copy(alpha = 0.2f)
        
        // Helper to draw line with subtle shadow for legibility
        fun drawGridLine(start: Offset, end: Offset) {
            drawLine(shadowColor, start + Offset(1f, 1f), end + Offset(1f, 1f), strokeWidth)
            drawLine(color, start, end, strokeWidth)
        }

        // Vertical lines
        drawGridLine(Offset(size.width / 3, 0f), Offset(size.width / 3, size.height))
        drawGridLine(Offset(2 * size.width / 3, 0f), Offset(2 * size.width / 3, size.height))
        
        // Horizontal lines
        drawGridLine(Offset(0f, size.height / 3), Offset(size.width, size.height / 3))
        drawGridLine(Offset(0f, 2 * size.height / 3), Offset(size.width, 2 * size.height / 3))
    }
}

@Composable
fun HorizonLeveler(tilt: Float) {
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
        val color = if (isLevel) Color.Yellow else Color.White
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
fun FocusRing(point: Offset) {
    val infiniteTransition = rememberInfiniteTransition(label = "focusPulse")
    
    val opacity by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusOpacity"
    )

    var startScale by remember { mutableStateOf(1.5f) }
    LaunchedEffect(point) {
        startScale = 1.5f
        // Small delay to trigger the scale down animation
    }

    val animatedScale by animateFloatAsState(
        targetValue = 1.0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "focusEntryScale"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sizePx = 70.dp.toPx()
        val color = Color.Yellow.copy(alpha = opacity)
        
        withTransform({
            translate(left = point.x * size.width - sizePx / 2, top = point.y * size.height - sizePx / 2)
            scale(animatedScale, animatedScale, pivot = Offset(sizePx / 2, sizePx / 2))
        }) {
            drawRect(
                color = color,
                size = Size(sizePx, sizePx),
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Small crosshair lines
            drawLine(color, Offset(sizePx / 2, 0f), Offset(sizePx / 2, 10f), 2.dp.toPx())
            drawLine(color, Offset(sizePx / 2, sizePx), Offset(sizePx / 2, sizePx - 10f), 2.dp.toPx())
            drawLine(color, Offset(0f, sizePx / 2), Offset(10f, sizePx / 2), 2.dp.toPx())
            drawLine(color, Offset(sizePx, sizePx / 2), Offset(sizePx - 10f, sizePx / 2), 2.dp.toPx())
        }
    }
}
