package com.fearmikey.camera.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fearmikey.camera.data.engine.RecordingPreviewTarget
import com.fearmikey.camera.domain.model.SlowMotion
import com.fearmikey.camera.ui.theme.ViewfinderTheme
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * Live view while Camera2 records (Slow motion, Time lapse). CameraX is released then, so the
 * PreviewView stops; this TextureView, laid over it with the viewfinder's bounds, receives the
 * recording session's preview stream (handed to the engine through [onTarget]). It is
 * transparent until the first frame. After a recording its last frame stays up as a freeze frame
 * until [previewView] streams again; then a fresh TextureView replaces it.
 *
 * If no frame arrives (the camera rejected the extra output), [fallbackText] dims the
 * viewfinder instead. [hint] is shown at the bottom while recording with a live view.
 */
@Composable
fun RecordingPreview(
    previewView: PreviewView?,
    isRecording: Boolean,
    fallbackText: String,
    onTarget: (RecordingPreviewTarget?) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(previewView) {
        val view = previewView ?: return@DisposableEffect onDispose {}
        val observer = androidx.lifecycle.Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) generation++
        }
        view.previewStreamState.observeForever(observer)
        onDispose { view.previewStreamState.removeObserver(observer) }
    }

    val latestOnTarget by rememberUpdatedState(onTarget)
    // The target handed out last, so a destroyed old texture can't clear a newer one.
    val current = remember { arrayOfNulls<RecordingPreviewTarget>(1) }
    var hasFrame by remember(generation) { mutableStateOf(false) }

    Box(modifier) {
        key(generation) {
            var streamSize by remember { mutableStateOf<Size?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            (v.tag as? Size)?.let { (v as TextureView).cropTo(it) }
                        }
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                val view = this@apply
                                val target = RecordingPreviewTarget(texture) { size -> view.post { streamSize = size } }
                                current[0] = target
                                latestOnTarget(target)
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                if (current[0]?.surfaceTexture === texture) {
                                    current[0] = null
                                    latestOnTarget(null)
                                }
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                if (!hasFrame) hasFrame = true
                            }
                        }
                    }
                },
                update = { view ->
                    streamSize?.let {
                        view.tag = it
                        view.cropTo(it)
                    }
                },
            )
        }

        var showFallback by remember { mutableStateOf(false) }
        LaunchedEffect(isRecording, hasFrame) {
            showFallback = false
            if (isRecording && !hasFrame) {
                delay(FALLBACK_DELAY_MS.milliseconds)
                showFallback = true
            }
        }
        if (showFallback) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Text(fallbackText, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            }
        } else if (hint != null && isRecording && hasFrame) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                Text(
                    hint,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Before showing the "no live view" text: the camera usually delivers its first frame within ~1 s. */
private const val FALLBACK_DELAY_MS = 1500L

private fun TextureView.cropTo(content: Size) {
    if (width == 0 || height == 0) return
    setTransform(recordingPreviewTransform(width.toFloat(), height.toFloat(), content, display?.rotation ?: 0))
}

/**
 * Center-crops ("fill center", like the PreviewView) a stream that is [content] sized when
 * upright in the device's natural orientation into a [viewW] x [viewH] TextureView on a display
 * turned by [displayRotation] (Surface.ROTATION_*, 0 on phones as the UI is portrait-locked).
 * TextureView stretches the buffer to its bounds: undo that, turn it against the display
 * rotation, then scale it uniformly until it covers the view.
 */
internal fun recordingPreviewTransform(viewW: Float, viewH: Float, content: Size, displayRotation: Int): Matrix {
    val cx = viewW / 2f
    val cy = viewH / 2f
    val cw = content.width.toFloat()
    val ch = content.height.toFloat()
    val sideways = displayRotation % 2 == 1
    val shownW = if (sideways) ch else cw
    val shownH = if (sideways) cw else ch
    val scale = max(viewW / shownW, viewH / shownH)
    return Matrix().apply {
        setScale(cw / viewW, ch / viewH, cx, cy)
        postRotate(-90f * displayRotation, cx, cy)
        postScale(scale, scale, cx, cy)
    }
}

/**
 * Slow-motion speed choice: the Speed tool's control in the adjustments row (ProToolControl,
 * where the zoom row sits). One segment per capture rate the camera offers, slowest first,
 * labeled by playback speed ("1/8x" = 240 fps, "1/4x" = 120 fps); the selected one is a pale
 * accent pill with the slow-motion glyph. With a single rate it is just a label.
 */
@Composable
fun SlowMotionSpeedControl(
    rates: List<Int>,
    selectedFps: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rates.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    val active = SlowMotion.resolveFps(selectedFps, rates)
    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(4.dp).height(44.dp).selectableGroup(),
        ) {
            rates.sortedDescending().forEach { fps ->
                val selected = fps == active
                val background by animateColorAsState(if (selected) CameraHighlight else Color.Transparent, label = "slowMoRate")
                val textColor by animateColorAsState(if (selected) OnCameraHighlight else Color.White, label = "slowMoRateText")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(background)
                        .selectable(selected = selected, enabled = rates.size > 1, role = Role.RadioButton) {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(fps)
                            }
                        }
                        .semantics { contentDescription = "${SlowMotion.speedLabel(fps)} speed, $fps frames per second" }
                        .padding(horizontal = 16.dp),
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.SlowMotionVideo,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(SlowMotion.speedLabel(fps), color = textColor, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Preview(name = "Slow motion speed", backgroundColor = 0xFF3A4A5A, showBackground = true)
@Composable
private fun SlowMotionSpeedControlPreview() {
    ViewfinderTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlowMotionSpeedControl(rates = listOf(120, 240), selectedFps = 120, onSelect = {})
            SlowMotionSpeedControl(rates = listOf(120, 240), selectedFps = 240, onSelect = {})
            SlowMotionSpeedControl(rates = listOf(120), selectedFps = 240, onSelect = {})
        }
    }
}
