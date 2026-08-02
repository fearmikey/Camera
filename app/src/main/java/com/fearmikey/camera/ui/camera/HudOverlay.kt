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
import kotlin.math.abs

@Composable
fun HudOverlay(
    gridMode: GridMode,
    showLeveler: Boolean,
    autoShowLeveler: Boolean,
    horizonTilt: Float,
    focusPoint: androidx.compose.ui.geometry.Offset?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (gridMode != GridMode.OFF) {
            CameraGrid(gridMode)
        }
        
        if (showLeveler || autoShowLeveler) {
            HorizonLeveler(tilt = horizonTilt)
        }
        
        focusPoint?.let { point ->
            FocusRing(point = point)
        }
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

    val animatedScale by animateFloatAsState(
        targetValue = 1.0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "focusEntryScale"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radiusPx = 40.dp.toPx()
        val color = Color.Yellow.copy(alpha = opacity)
        
        withTransform({
            translate(left = point.x * size.width, top = point.y * size.height)
            scale(animatedScale, animatedScale, pivot = Offset.Zero)
        }) {
            drawCircle(
                color = color,
                radius = radiusPx,
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Subtle inner circle
            drawCircle(
                color = color.copy(alpha = opacity * 0.5f),
                radius = radiusPx * 0.8f,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
