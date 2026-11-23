package com.fearmikey.camera.data.engine

import android.graphics.SurfaceTexture
import android.util.Size

/**
 * Where Camera2 recordings (Slow motion, Time lapse) show their live view. CameraX is released
 * while they record, so its PreviewView stops; the UI hands a TextureView's [surfaceTexture]
 * to [CaptureEngine.setRecordingPreview] instead and the recording session adds it as a second
 * output.
 */
class RecordingPreviewTarget(
    val surfaceTexture: SurfaceTexture,
    /**
     * Called (Camera2 background thread) before frames arrive with the stream's size as seen
     * upright in the device's natural orientation (sensor rotation already applied, which is
     * what the SurfaceTexture transform does), so the view can center-crop it.
     */
    val onStreamSize: (Size) -> Unit,
)
