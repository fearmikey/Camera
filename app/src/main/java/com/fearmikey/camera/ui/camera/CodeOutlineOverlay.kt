package com.fearmikey.camera.ui.camera

import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.data.engine.analysis.CodeScanFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Outlines the QR codes/barcodes currently in view on top of the viewfinder. Corners come from
 * the analysis frame and are mapped onto the [previewView] with CameraX's CoordinateTransform,
 * so they line up regardless of aspect ratio, crop or camera facing. Must be laid out with the
 * same bounds as [previewView].
 */
@androidx.annotation.OptIn(TransformExperimental::class)
@Composable
fun CodeOutlineOverlay(
    frameFlow: StateFlow<CodeScanFrame?>,
    previewView: PreviewView?,
    modifier: Modifier = Modifier,
) {
    val frame by frameFlow.collectAsState()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val cache = remember { TransformCache() }

    // Target polygons (view pixels) keyed by code text.
    val targets: Map<String, FloatArray> = remember(frame, previewView, viewSize) {
        val f = frame ?: return@remember emptyMap()
        val transform = cache.get(f.transform, previewView, viewSize) ?: return@remember emptyMap()
        f.codes.asSequence()
            .filter { it.text in f.inView && it.corners.size == 8 }
            .associate { code ->
                val pts = FloatArray(8) { i -> code.corners[i] * if (i % 2 == 0) f.frameWidth else f.frameHeight }
                transform.mapPoints(pts)
                code.text to pts
            }
    }

    // Glide between analysis updates (~7 Hz) instead of jumping.
    var from by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    var to by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(targets) {
        if (targets.isEmpty()) return@LaunchedEffect // keep the last outline while it fades out
        from = interpolate(from, to, progress.value)
        to = targets
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 140, easing = LinearOutSlowInEasing))
    }
    val alpha by animateFloatAsState(
        targetValue = if (targets.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = if (targets.isNotEmpty()) 120 else 300),
        label = "codeOutlineAlpha"
    )

    val accent = CameraAccent
    Canvas(modifier = modifier.onSizeChanged { viewSize = it }) {
        if (alpha <= 0f) return@Canvas
        val strokeWidth = 3.dp.toPx()
        interpolate(from, to, progress.value).values.forEach { pts ->
            val path = Path().apply {
                moveTo(pts[0], pts[1])
                for (i in 1 until 4) lineTo(pts[i * 2], pts[i * 2 + 1])
                close()
            }
            drawPath(path, accent.copy(alpha = 0.18f * alpha))
            drawPath(path, accent.copy(alpha = alpha), style = Stroke(width = strokeWidth, join = StrokeJoin.Round))
        }
    }
}

/** Corner-wise lerp from [from] to [to]; codes new in [to] appear in place. */
private fun interpolate(from: Map<String, FloatArray>, to: Map<String, FloatArray>, t: Float): Map<String, FloatArray> {
    if (t >= 1f) return to
    return to.mapValues { (key, target) ->
        val start = from[key] ?: return@mapValues target
        FloatArray(8) { i -> start[i] + (target[i] - start[i]) * t }
    }
}

/**
 * Caches the analysis->preview CoordinateTransform. The analyzer reuses one source transform per
 * binding, and the preview transform only changes with the view size or a rebind (which also
 * creates a new source), so recomputing on those keys is enough. Nulls are not cached because the
 * preview transform is unavailable until the first frame is shown. Shared with
 * PortraitFaceOverlay.
 */
@androidx.annotation.OptIn(TransformExperimental::class)
internal class TransformCache {
    private var source: OutputTransform? = null
    private var view: PreviewView? = null
    private var size = IntSize.Zero
    private var transform: CoordinateTransform? = null

    fun get(newSource: OutputTransform?, newView: PreviewView?, newSize: IntSize): CoordinateTransform? {
        if (newSource == null || newView == null || newSize == IntSize.Zero) return null
        val cached = transform
        if (cached != null && newSource === source && newView === view && newSize == size) return cached
        val target = newView.outputTransform ?: return null
        return CoordinateTransform(newSource, target).also {
            source = newSource
            view = newView
            size = newSize
            transform = it
        }
    }
}
