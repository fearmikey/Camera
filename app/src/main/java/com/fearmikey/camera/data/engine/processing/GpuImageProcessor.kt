package com.fearmikey.camera.data.engine.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Handles GPU-accelerated image processing using OpenGL ES 3.0.
 * Ensures all GL operations happen on a consistent thread context.
 */
class GpuImageProcessor {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var binningProgramId: Int = -1
    private var binning2DProgramId: Int = -1
    private var vertexBuffer: FloatBuffer

    init {
        // Standard texture coordinates.
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 
            1f, -1f, 1f, 0f,  
            -1f, 1f, 0f, 1f,  
            1f, 1f, 1f, 1f    
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)
        
        initEGL()
    }

    fun loadBinningShader(vertexSource: String, fragmentOESSource: String, fragment2DSource: String) {
        makeCurrent()
        binningProgramId = createProgram(vertexSource, fragmentOESSource)
        binning2DProgramId = createProgram(vertexSource, fragment2DSource)
        releaseCurrent()
        android.util.Log.i("GpuImageProcessor", "Shaders loaded: OES=$binningProgramId, 2D=$binning2DProgramId")
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        android.util.Log.i("GpuImageProcessor", "EGL Initialized")
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            android.util.Log.e("GpuImageProcessor", "eglMakeCurrent failed")
        }
    }

    private fun releaseCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun performPixelBinning(imageProxy: ImageProxy, rotationDegrees: Int): Bitmap? {
        android.util.Log.i("GpuImageProcessor", "performPixelBinning START: rotation=$rotationDegrees")
        
        val inputBitmap = imageProxyToBitmap(imageProxy)
        if (inputBitmap == null) {
            android.util.Log.e("GpuImageProcessor", "Failed to convert imageProxy to Bitmap")
            return null
        }

        makeCurrent()
        
        val inputWidth = inputBitmap.width
        val inputHeight = inputBitmap.height
        val outputWidth = inputWidth / 4
        val outputHeight = inputHeight / 4

        val inputTexId = IntArray(1)
        GLES30.glGenTextures(1, inputTexId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, inputBitmap, 0)
        inputBitmap.recycle()

        val outputTexId = IntArray(1)
        GLES30.glGenTextures(1, outputTexId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexId[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, outputWidth, outputHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        
        val fboId = IntArray(1)
        GLES30.glGenFramebuffers(1, fboId, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outputTexId[0], 0)

        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        
        val programId = binning2DProgramId
        if (programId <= 0) {
            android.util.Log.e("GpuImageProcessor", "2D Program invalid: $programId")
            releaseCurrent()
            return null
        }

        GLES30.glUseProgram(programId)
        val posLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val texCoordLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        val texLoc = GLES30.glGetUniformLocation(programId, "uTexture")
        val sizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glEnableVertexAttribArray(texCoordLoc)
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId[0])
        GLES30.glUniform1i(texLoc, 0)
        GLES30.glUniform2f(sizeLoc, 1.0f / inputWidth, 1.0f / inputHeight)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texCoordLoc)

        val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
        buffer.order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, outputWidth, outputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        resultBitmap.copyPixelsFromBuffer(buffer)

        // 5. Rotate correction
        // On this device, glReadPixels matches the Bitmap coordinate origin.
        // We only need to apply the sensor's mounting rotation.
        val finalMatrix = Matrix()
        finalMatrix.postRotate(rotationDegrees.toFloat())
        
        val finalBitmap = Bitmap.createBitmap(
            resultBitmap, 0, 0, outputWidth, outputHeight, finalMatrix, true
        )
        
        GLES30.glDeleteTextures(1, inputTexId, 0)
        GLES30.glDeleteTextures(1, outputTexId, 0)
        GLES30.glDeleteFramebuffers(1, fboId, 0)
        releaseCurrent()
        
        resultBitmap.recycle()
        android.util.Log.i("GpuImageProcessor", "performPixelBinning SUCCESS")
        return finalBitmap
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            if (image.format == android.graphics.ImageFormat.JPEG) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else if (image.format == android.graphics.ImageFormat.YUV_420_888) {
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer
                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()
                val nv21 = ByteArray(ySize + uSize + vSize)
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
                val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
                val imageBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GpuImageProcessor", "Error converting imageProxy to Bitmap", e)
            null
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            android.util.Log.e("GpuImageProcessor", "Program link error: " + GLES30.glGetProgramInfoLog(program))
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            android.util.Log.e("GpuImageProcessor", "Shader compile error: " + GLES30.glGetShaderInfoLog(shader))
            return 0
        }
        return shader
    }

    fun initAccumulator(image: Image) {}
    fun mergeFrame(image: Image, matrix: Matrix, weight: Float) {}
    fun readResult(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }
}
