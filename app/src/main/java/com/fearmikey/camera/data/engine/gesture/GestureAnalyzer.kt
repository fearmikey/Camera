package com.fearmikey.camera.data.engine.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Clean, production-ready GestureAnalyzer for Hand Trigger.
 * Implements frame skipping, smoothing, and state management.
 */
class GestureAnalyzer(
    private val context: Context,
    private val onGestureTriggered: (GestureResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "GestureAnalyzer"
    }

    // Sealed class for state management
    sealed class State {
        object Searching : State()
        object HandDetected : State()
        object Cooldown : State()
    }

    private var handLandmarker: HandLandmarker? = null
    private var faceDetector: FaceDetector? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isInitializing = false
    
    private var faceDetected = false
    private var lastFaceTimestamp = 0L
    private val faceTimeout = 2000L // Keep hand detection active for 2s after face is lost

    private var frameCounter = 0
    private val frameSkip = 5 // Increased skip to 5 to process fewer frames (reduces processing overhead)
    
    private var consecutiveDetections = 0
    private val requiredConsecutiveFrames = 3 // Balanced sensitivity (was 2)
    
    private var currentState: State = State.Searching
    private var lastTriggerTime = 0L
    private val cooldownMillis = 8000L // Prevent re-triggering during/immediately after 3s timer

    init {
        // Deferred initialization to avoid blocking the main thread or engine binding
    }

    private fun setupHandLandmarker() {
        if (handLandmarker != null) {
            Log.d(TAG, "HandLandmarker already exists, skipping setup.")
            return
        }
        
        Log.d(TAG, "START: Gesture models setup (isInitializing=$isInitializing)")
        
        val handModelPath = "models/hand_landmarker.task"
        val faceModelNames = listOf("models/blaze_face_short_range.tflite", "models/blaze_face_full_range.tflite", "models/face_detector.task")
        
        try {
            // 1. Setup Hand Landmarker (Primary requirement)
            val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(handModelPath).setDelegate(Delegate.CPU).build())
                .setMinHandDetectionConfidence(0.2f)
                .setMinTrackingConfidence(0.2f)
                .setMinHandPresenceConfidence(0.2f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            Log.d(TAG, "Initializing HandLandmarker...")
            handLandmarker = HandLandmarker.createFromOptions(context, handOptions)
            Log.i(TAG, "SUCCESS: HandLandmarker initialized")

            // 2. Setup Face Detector (Optional Gating)
            var faceModelPath: String? = null
            for (name in faceModelNames) {
                try {
                    context.assets.open(name).close()
                    faceModelPath = name
                    break
                } catch (ignored: Exception) {}
            }

            if (faceModelPath != null) {
                Log.d(TAG, "Attempting to load face model: $faceModelPath")
                val faceOptions = FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(faceModelPath).setDelegate(Delegate.CPU).build())
                    .setMinDetectionConfidence(0.4f)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                
                val detector = FaceDetector.createFromOptions(context, faceOptions)
                
                // CRITICAL: Test the face detector to see if it crashes (tensor mismatch check)
                try {
                    val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    val dummyImage = BitmapImageBuilder(dummyBitmap).build()
                    detector.detect(dummyImage)
                    faceDetector = detector
                    Log.i(TAG, "SUCCESS: FaceDetector initialized and verified")
                } catch (e: Exception) {
                    Log.e(TAG, "FaceDetector validation failed (likely wrong model type): ${e.message}")
                    detector.close()
                }
            } else {
                Log.w(TAG, "Face model not found. Gated detection disabled.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Gesture models setup failed: ${e.message}")
            e.printStackTrace()
        } finally {
            isInitializing = false
            Log.d(TAG, "END: Gesture models setup")
        }
    }

    override fun analyze(image: ImageProxy) {
        if (handLandmarker == null || faceDetector == null) {
            if (!isInitializing) {
                isInitializing = true
                backgroundExecutor.execute { setupHandLandmarker() }
            }
            image.close()
            return
        }

        // Cooldown expiration check
        if (currentState is State.Cooldown) {
            if (System.currentTimeMillis() - lastTriggerTime > cooldownMillis) {
                currentState = State.Searching
            }
        }

        // Sampling check
        frameCounter++
        if (frameCounter % frameSkip != 0) {
            image.close()
            return
        }

        // Convert ImageProxy to MPImage
        val rawBitmap = imageToBitmap(image)
        if (rawBitmap == null) {
            image.close()
            return
        }
        val bitmap = rawBitmap.rotate(image.imageInfo.rotationDegrees.toFloat())
        val mpImage = BitmapImageBuilder(bitmap).build()

        try {
            // 1. Run Face Detection (if available)
            var isFaceRecentlySeen = true 
            var faceBoundingBox: Rect? = null
            
            val detector = faceDetector
            if (detector != null) {
                try {
                    val faceResult = detector.detect(mpImage)
                    val detections = faceResult.detections()
                    faceDetected = detections.isNotEmpty()
                    if (faceDetected) {
                        lastFaceTimestamp = System.currentTimeMillis()
                        // Extract face bounding box
                        val face = detections[0].boundingBox()
                        faceBoundingBox = Rect(
                            face.left / mpImage.width,
                            face.top / mpImage.height,
                            face.right / mpImage.width,
                            face.bottom / mpImage.height
                        )
                    }
                    isFaceRecentlySeen = (System.currentTimeMillis() - lastFaceTimestamp) < faceTimeout
                } catch (e: Exception) {
                    if (frameCounter % 100 == 0) Log.w(TAG, "Face detection failed: ${e.message}")
                    // Don't disable isFaceRecentlySeen, let it stay true as fallback
                }
            }

            // 2. Only run Hand Detection if a face is recently seen (or detector is disabled)
            if (isFaceRecentlySeen) {
                val handResult = handLandmarker?.detect(mpImage)
                if (handResult != null) {
                    processResult(handResult, faceBoundingBox)
                }
            } else {
                // Clear tracking boxes if no face
                onGestureTriggered(GestureResult(null, null, false))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Analysis failed: ${error.message}")
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        return try {
            if (image.format == android.graphics.ImageFormat.JPEG) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                // Efficient YUV_420_888 to Bitmap conversion
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                // U and V are swapped
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
                val imageBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun processResult(result: HandLandmarkerResult, faceBox: Rect?) {
        val landmarks = result.landmarks()
        
        if (landmarks.isNotEmpty()) {
            val hand = landmarks[0]
            val wrist = hand[0] // Landmark index 0 is the Wrist
            
            // Calculate bounding box for UI overlay
            var minX = 1f
            var maxX = 0f
            var minY = 1f
            var maxY = 0f
            for (landmark in hand) {
                minX = minX.coerceAtMost(landmark.x())
                maxX = maxX.coerceAtLeast(landmark.x())
                minY = minY.coerceAtMost(landmark.y())
                maxY = maxY.coerceAtLeast(landmark.y())
            }
            val handBoundingBox = Rect(minX, minY, maxX, maxY)

            // Detection Logic (More lenient):
            // 1. Wrist and palm should be visible and somewhat upright
            // 2. Hand must occupy enough space
            val middleFingerTip = hand[12]
            val handSize = Math.sqrt(
                Math.pow((wrist.x() - middleFingerTip.x()).toDouble(), 2.0) +
                Math.pow((wrist.y() - middleFingerTip.y()).toDouble(), 2.0)
            )

            val isUpright = middleFingerTip.y() < wrist.y() // Fingers above wrist
            val isCloseEnough = handSize > 0.12 
            val isCentered = wrist.x() in 0.1f..0.9f

            if (isUpright && isCloseEnough && isCentered) {
                consecutiveDetections++
                if (currentState !is State.HandDetected && currentState !is State.Cooldown) {
                    currentState = State.HandDetected
                }
                
                if (consecutiveDetections >= requiredConsecutiveFrames && currentState !is State.Cooldown) {
                    triggerGesture(handBoundingBox, faceBox)
                } else {
                    // Report detection without triggering timer (tracking only)
                    onGestureTriggered(GestureResult(handBoundingBox, faceBox, false))
                }
            } else {
                consecutiveDetections = 0
                if (currentState is State.HandDetected) {
                    currentState = State.Searching
                }
                onGestureTriggered(GestureResult(null, faceBox, false))
            }
        } else {
            consecutiveDetections = 0
            onGestureTriggered(GestureResult(null, faceBox, false))
        }
    }

    private fun triggerGesture(handBox: Rect, faceBox: Rect?) {
        if (currentState != State.Cooldown) {
            currentState = State.Cooldown
            lastTriggerTime = System.currentTimeMillis()
            consecutiveDetections = 0
            onGestureTriggered(GestureResult(handBox, faceBox, true))
        }
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        if (degrees == 0f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    fun close() {
        try {
            backgroundExecutor.execute {
                handLandmarker?.close()
                faceDetector?.close()
                handLandmarker = null
                faceDetector = null
            }
            backgroundExecutor.shutdown()
        } catch (ignored: Exception) {}
    }
}
