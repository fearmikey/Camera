package com.fearmikey.camera.data.engine.gesture

import androidx.compose.ui.geometry.Rect

data class GestureResult(
    val handBoundingBox: Rect?,
    val faceBoundingBox: Rect?,
    val isTriggered: Boolean
)
