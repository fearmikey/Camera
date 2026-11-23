package com.fearmikey.camera.ui.camera

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.domain.model.PanoramaHint
import com.fearmikey.camera.domain.model.PanoramaProgress
import kotlin.math.roundToInt

private val GuideYellow = Color(0xFFF6C344)

/**
 * Panorama guide drawn inside the viewfinder (centered on it).
 *
 * A hollow target circle with a short yellow horizon line either side and a white arrow on
 * the line pointing the way to pan. While sweeping, a white dot marks where the next frame
 * is taken: move it into the circle and pause briefly; the circle and dot turn yellow while
 * it's lined up. Before the sweep the circle holds a white dot and a hint pill explains it.
 *
 * @param progress latest sweep progress (null right after the shutter, or idle).
 * @param capturing a sweep is running.
 * @param showIdleHint show the "tap shutter" pill (hidden while another message is shown).
 * @param pillTop distance of the hint pill from the viewfinder top (keep it clear of the
 *   corner preview).
 */
@Composable
fun PanoramaViewfinderGuide(
    progress: PanoramaProgress?,
    capturing: Boolean,
    showIdleHint: Boolean,
    modifier: Modifier = Modifier,
    pillTop: Dp = 20.dp,
) {
    val sweep = progress?.takeIf { capturing && !it.isIdlePreview }
    val direction = sweep?.direction ?: 0
    val aligned = sweep?.aligned == true
    val step = sweep?.targetStep ?: 0.33f
    val dx = sweep?.keyframeDx ?: 0f
    // Where the next dot is relative to the view center (fraction of width); negative once
    // the user has panned past it, so the arrow points back to it.
    val dotOffset = direction * step - dx
    val passedDot = direction != 0 && dotOffset * direction < -0.12f
    val arrowRight = if (direction != 0) dotOffset > 0f else false
    val dotY by animateFloatAsState(
        targetValue = -(sweep?.verticalOffset ?: 0f),
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "panoDotY"
    )

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringR = 18.dp.toPx()
            val lineHalf = 52.dp.toPx()
            val lineStroke = 1.5.dp.toPx()

            // Short yellow horizon either side of the target.
            drawLine(GuideYellow, Offset(c.x - lineHalf, c.y), Offset(c.x - ringR, c.y), lineStroke)
            drawLine(GuideYellow, Offset(c.x + ringR, c.y), Offset(c.x + lineHalf, c.y), lineStroke)

            // Arrow(s) on the line, hidden while a dot is lined up.
            if (!aligned) {
                when {
                    // Either way is fine until the sweep's first movement picks a direction.
                    direction != 0 -> drawArrow(
                        Offset(c.x + (if (arrowRight) 1 else -1) * 82.dp.toPx(), c.y),
                        pointsRight = arrowRight
                    )
                    else -> {
                        drawArrow(Offset(c.x - 82.dp.toPx(), c.y), pointsRight = false)
                        drawArrow(Offset(c.x + 82.dp.toPx(), c.y), pointsRight = true)
                    }
                }
            }

            // Target circle.
            drawCircle(
                color = if (aligned) GuideYellow else Color.White,
                radius = ringR,
                center = c,
                style = Stroke(width = if (aligned) 3.dp.toPx() else 2.dp.toPx())
            )

            val dotR = 5.5.dp.toPx()
            val margin = 10.dp.toPx()
            fun dotAt(fractionX: Float) = Offset(
                (c.x + fractionX * size.width).coerceIn(margin, size.width - margin),
                c.y + dotY * size.height
            )
            when {
                !capturing -> drawCircle(Color.White, dotR, c)
                direction != 0 -> drawCircle(if (aligned) GuideYellow else Color.White, dotR, dotAt(dotOffset))
                else -> {
                    drawCircle(Color.White, dotR, dotAt(-step - dx))
                    drawCircle(Color.White, dotR, dotAt(step - dx))
                }
            }
        }

        val message = when {
            !capturing && showIdleHint -> PanoramaHint.START.message
            sweep?.hint == PanoramaHint.SLOW_DOWN || sweep?.hint == PanoramaHint.LOST -> sweep.hint.message
            passedDot -> "Go back to the dot"
            else -> null
        }
        if (message != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = pillTop, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Filled, slightly rounded triangle centered at [center]. */
private fun DrawScope.drawArrow(center: Offset, pointsRight: Boolean) {
    val half = 10.dp.toPx()
    val dir = if (pointsRight) 1f else -1f
    val path = Path().apply {
        moveTo(center.x + dir * half * 0.9f, center.y)
        lineTo(center.x - dir * half * 0.7f, center.y - half)
        lineTo(center.x - dir * half * 0.7f, center.y + half)
        close()
    }
    drawPath(path, Color.White, style = Fill)
    drawPath(path, Color.White, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * Corner preview: the live view before the sweep, then the stitched-so-far mosaic (incl.
 * the live view) growing from the corner while sweeping. Only captured content is drawn.
 */
@Composable
fun PanoramaStrip(progress: PanoramaProgress, modifier: Modifier = Modifier) {
    val bitmap = progress.preview ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val lo = progress.contentStart.coerceIn(0f, 1f)
    val hi = progress.contentEnd.coerceIn(0f, 1f)
    if (hi <= lo) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier.height(StripHeight)) {
        val srcW = ((hi - lo) * bitmap.width).roundToInt().coerceIn(1, bitmap.width)
        val srcX = (lo * bitmap.width).roundToInt().coerceIn(0, bitmap.width - srcW)
        val srcH = bitmap.height
        with(density) {
            var dstH = StripHeight.toPx()
            var dstW = srcW * dstH / srcH
            val maxW = maxWidth.toPx()
            if (dstW > maxW) {
                dstH *= maxW / dstW
                dstW = maxW
            }
            val shape = RoundedCornerShape(if (progress.isIdlePreview) 12.dp else 2.dp)
            Canvas(Modifier.size(dstW.toDp(), dstH.toDp()).clip(shape)) {
                drawImage(
                    image,
                    srcOffset = IntOffset(srcX, 0),
                    srcSize = IntSize(srcW, srcH),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
    }
}

/** Height of the corner preview ([PanoramaStrip]). */
val PanoramaStripHeight = 112.dp
private val StripHeight = PanoramaStripHeight

/** Black panorama glyph for the idle shutter (wide frame with bowed top and bottom). */
@Composable
fun PanoramaShutterGlyph(modifier: Modifier = Modifier, color: Color = Color.Black) {
    Canvas(modifier.size(width = 34.dp, height = 22.dp)) {
        val w = size.width
        val h = size.height
        val bow = h * 0.12f
        val path = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(w / 2f, bow * 2f, w, 0f)
            lineTo(w, h)
            quadraticBezierTo(w / 2f, h - bow * 2f, 0f, h)
            close()
        }
        drawPath(path, color)
    }
}

@Preview(name = "Sweeping", widthDp = 400, heightDp = 533, backgroundColor = 0xFF5A5048, showBackground = true)
@Composable
private fun PanoramaGuideSweepingPreview() {
    PanoramaViewfinderGuide(
        progress = PanoramaProgress(direction = -1, keyframeDx = -0.05f, targetStep = 0.33f, verticalOffset = 0.02f),
        capturing = true,
        showIdleHint = false,
    )
}

@Preview(name = "Aligned", widthDp = 400, heightDp = 533, backgroundColor = 0xFF5A5048, showBackground = true)
@Composable
private fun PanoramaGuideAlignedPreview() {
    PanoramaViewfinderGuide(
        progress = PanoramaProgress(direction = -1, keyframeDx = -0.32f, aligned = true, verticalOffset = 0.01f),
        capturing = true,
        showIdleHint = false,
    )
}

@Preview(name = "Idle", widthDp = 400, heightDp = 533, backgroundColor = 0xFF5A5048, showBackground = true)
@Composable
private fun PanoramaGuideIdlePreview() {
    PanoramaViewfinderGuide(progress = null, capturing = false, showIdleHint = true)
}
