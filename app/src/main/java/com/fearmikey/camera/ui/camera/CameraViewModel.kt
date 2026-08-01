package com.fearmikey.camera.ui.camera

import android.net.Uri
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.fearmikey.camera.data.engine.CaptureEngine
import com.fearmikey.camera.domain.model.CameraMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CameraViewModel(
    private val captureEngine: CaptureEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun bindToLifecycle(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        captureEngine.bindToLifecycle(lifecycleOwner, surfaceProvider)
    }

    fun setMode(mode: CameraMode) {
        _uiState.update { it.copy(currentMode = mode) }
        captureEngine.setMode(mode)
    }

    fun takePicture() {
        captureEngine.capturePhoto(
            onSuccess = { path ->
                _uiState.update { it.copy(lastCapturedUri = Uri.parse(path)) }
            },
            onError = { error ->
                error.printStackTrace()
            }
        )
    }

    fun startVideo() {
        _uiState.update { it.copy(isRecording = true) }
        if (_uiState.value.currentMode == CameraMode.SLOW_MO) {
            captureEngine.startSlowMoVideo(
                onSuccess = { path ->
                    _uiState.update { it.copy(lastCapturedUri = Uri.parse(path)) }
                },
                onError = { error ->
                    error.printStackTrace()
                    _uiState.update { it.copy(isRecording = false) }
                }
            )
        }
    }

    fun stopVideo() {
        _uiState.update { it.copy(isRecording = false) }
        if (_uiState.value.currentMode == CameraMode.SLOW_MO) {
            captureEngine.stopSlowMoVideo()
        }
    }

    fun toggleProMode() {
        _uiState.update { it.copy(isProMode = !it.isProMode) }
    }
}
