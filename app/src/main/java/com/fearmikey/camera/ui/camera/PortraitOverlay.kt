package com.fearmikey.camera.ui.camera

import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.data.engine.analysis.PortraitFaceFrame
import com.fearmikey.camera.domain.model.PortraitAperture
import com.fearmikey.camera.ui.theme.ViewfinderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Portrait mode viewfinder overlay: brackets around the faces that will be kept sharp
 * (mapped from the analysis frame onto [previewView] with CameraX's CoordinateTransform)
 * and a framing hint. Must be laid out with the same bounds as [previewView].
 */
@androidx.annotation.OptIn(TransformExperimental::class)
@Composable
fun PortraitFaceOverlay(
    frameFlow: StateFlow<PortraitFaceFrame>,
    previewView: PreviewView?,
    modifier: Modifier = Modifier,
) {
    val frame by frameFlow.collectAsState()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val cache = remember { TransformCache() }

    val targets: List<Rect> = remember(frame, previewView, viewSize) {
        val transform = cache.get(frame.transform, previewView, viewSize) ?: return@remember emptyList()
        frame.faces.map { f ->
            val pts = floatArrayOf(
                f.left * frame.frameWidth, f.top * frame.frameHeight,
                f.right * frame.frameWidth, f.bottom * frame.frameHeight,
            )
            transform.mapPoints(pts)
            // Mirroring (front camera) can swap the corners.
            Rect(minOf(pts[0], pts[2]), minOf(pts[1], pts[3]), maxOf(pts[0], pts[2]), maxOf(pts[1], pts[3]))
        }
    }

    // A face is "missing" only after a short grace period, so the hint doesn't flicker.
    val hasFaces = frame.faces.isNotEmpty()
    var noFace by remember { mutableStateOf(false) }
    LaunchedEffect(hasFaces) {
        noFace = false
        if (!hasFaces) {
            delay(1500.milliseconds)
            noFace = true
        }
    }
    val tooFar = frame.faces.firstOrNull()?.let { it.width() < MIN_FACE_WIDTH } == true
    val hint = when {
        noFace -> "No face found · blur centers on the subject"
        tooFar -> "Move closer for a stronger effect"
        else -> null
    }

    Box(modifier.onSizeChanged { viewSize = it }) {
        PortraitFaceGuide(faces = targets, hint = hint, modifier = Modifier.fillMaxSize())
    }
}

/** Draws face brackets (gliding between ~10 Hz updates) and the hint pill. */
@Composable
fun PortraitFaceGuide(
    faces: List<Rect>,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    var from by remember { mutableStateOf<List<Rect>>(emptyList()) }
    var to by remember { mutableStateOf(faces) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(faces) {
        if (faces.isEmpty()) return@LaunchedEffect // keep the last boxes while they fade out
        from = lerpRects(from, to, progress.value)
        to = faces
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 160, easing = LinearOutSlowInEasing))
    }
    val alpha by animateFloatAsState(
        targetValue = if (faces.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = if (faces.isNotEmpty()) 150 else 400),
        label = "portraitFaceAlpha"
    )

    val accent = CameraAccent
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (alpha <= 0f) return@Canvas
            lerpRects(from, to, progress.value).forEachIndexed { i, rect ->
                // The main subject in the accent color, other people in white.
                drawBrackets(rect, (if (i == 0) accent else Color.White).copy(alpha = alpha * if (i == 0) 1f else 0.8f))
            }
        }
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
        ) {
            // Keep the last text (plain holder, not state) so it doesn't vanish mid-fade.
            val lastHint = remember { arrayOf("") }
            if (hint != null) lastHint[0] = hint
            Text(
                text = lastHint[0],
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

private fun DrawScope.drawBrackets(rect: Rect, color: Color) {
    val len = minOf(rect.width, rect.height) * 0.24f
    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    val path = Path().apply {
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            moveTo(x, y + dy * len)
            lineTo(x, y)
            lineTo(x + dx * len, y)
        }
        corner(rect.left, rect.top, 1f, 1f)
        corner(rect.right, rect.top, -1f, 1f)
        corner(rect.left, rect.bottom, 1f, -1f)
        corner(rect.right, rect.bottom, -1f, -1f)
    }
    drawPath(path, color, style = stroke)
}

/** Index-wise lerp (faces arrive largest first); new faces appear in place. */
private fun lerpRects(from: List<Rect>, to: List<Rect>, t: Float): List<Rect> {
    if (t >= 1f) return to
    return to.mapIndexed { i, target ->
        val start = from.getOrNull(i) ?: return@mapIndexed target
        Rect(
            start.left + (target.left - start.left) * t,
            start.top + (target.top - start.top) * t,
            start.right + (target.right - start.right) * t,
            start.bottom + (target.bottom - start.bottom) * t,
        )
    }
}

/** Faces narrower than this (fraction of the frame) get too little separation. */
private const val MIN_FACE_WIDTH = 0.1f

/**
 * Simulated-aperture control for Portrait mode: a compact "ƒ/2.8" pill that expands into a
 * slider (right = more blur). Collapses again after a few idle seconds.
 */
@Composable
fun PortraitApertureControl(
    aperture: Float,
    onApertureChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val haptic = LocalHapticFeedback.current
    val stops = PortraitAperture.stops
    // Slider position 0 = least blur (largest f-number).
    val position = stops.size - 1 - stops.indexOf(PortraitAperture.nearest(aperture))

    LaunchedEffect(expanded, aperture) {
        if (expanded && !initiallyExpanded) {
            delay(4.seconds)
            expanded = false
        }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.animateContentSize()
    ) {
        if (!expanded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expanded = true }
                    .semantics { contentDescription = "Background blur ${PortraitAperture.label(aperture)}" }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    PortraitAperture.label(aperture),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(296.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { expanded = false }
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null, tint = CameraAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Blur ${PortraitAperture.label(aperture)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = position.toFloat(),
                    onValueChange = { v ->
                        val index = stops.size - 1 - v.toInt().coerceIn(0, stops.size - 1)
                        val f = stops[index]
                        if (f != PortraitAperture.nearest(aperture)) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onApertureChange(f)
                        }
                    },
                    valueRange = 0f..(stops.size - 1).toFloat(),
                    steps = stops.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = CameraAccent,
                        activeTrackColor = CameraAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        activeTickColor = OnCameraAccent.copy(alpha = 0.6f),
                        inactiveTickColor = Color.White.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.semantics { contentDescription = "Background blur" }
                )
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(PortraitAperture.label(stops.last()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(PortraitAperture.label(stops.first()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Preview(name = "Portrait guide", widthDp = 360, heightDp = 480, backgroundColor = 0xFF3A4A5A, showBackground = true)
@Composable
private fun PortraitFaceGuidePreview() {
    ViewfinderTheme {
        PortraitFaceGuide(
            faces = listOf(Rect(Offset(330f, 260f), Offset(620f, 640f)), Rect(Offset(700f, 380f), Offset(860f, 590f))),
            hint = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(name = "Portrait hint", widthDp = 360, heightDp = 200, backgroundColor = 0xFF3A4A5A, showBackground = true)
@Composable
private fun PortraitHintPreview() {
    ViewfinderTheme {
        PortraitFaceGuide(faces = emptyList(), hint = "No face found · blur centers on the subject", modifier = Modifier.fillMaxSize())
    }
}

@Preview(name = "Aperture control", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PortraitApertureControlPreview() {
    ViewfinderTheme {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PortraitApertureControl(aperture = 2.8f, onApertureChange = {})
            Spacer(Modifier.size(16.dp))
            PortraitApertureControl(aperture = 1.8f, onApertureChange = {}, initiallyExpanded = true)
        }
    }
}
