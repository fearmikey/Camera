package com.fearmikey.camera.data.engine.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Clean, production-ready GestureAnalyzer for Hand Trigger.
 * Implements frame skipping, smoothing, and state management.
 */
class GestureAnalyzer(
    private val context: Context,
    private val onGestureTriggered: () -> Unit
) : ImageAnalysis.Analyzer {

    // Sealed class for state management
    sealed class State {
        object Searching : State()
        object HandDetected : State()
        object Cooldown : State()
    }

    private var handLandmarker: HandLandmarker? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var frameCounter = 0
    private val frameSkip = 5 // Challenge 1: The "Sampling" Problem (Analyze every 5th frame)
    
    private var consecutiveDetections = 0
    private val requiredConsecutiveFrames = 3 // Challenge 3: The "Latency" Problem (Smoothing Filter)
    
    private var currentState: State = State.Searching
    private var lastTriggerTime = 0L
    private val cooldownMillis = 5000L // Prevents multiple triggers during one raise

    init {
        backgroundExecutor.execute { setupHandLandmarker() }
    }

    private fun setupHandLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setDelegate(Delegate.GPU)
            // Note: hand_landmarker.task must be present in the app's assets folder
            .setModelAssetPath("hand_landmarker.task")

        val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setMinHandDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setNumHands(1)
            .setRunningMode(RunningMode.IMAGE)

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun analyze(image: ImageProxy) {
        // Cooldown check
        if (currentState is State.Cooldown) {
            if (System.currentTimeMillis() - lastTriggerTime > cooldownMillis) {
                currentState = State.Searching
            } else {
                image.close()
                return
            }
        }

        // Sampling check
        frameCounter++
        if (frameCounter % frameSkip != 0) {
            image.close()
            return
        }

        val landmarker = handLandmarker
        if (landmarker == null) {
            image.close()
            return
        }

        // Convert ImageProxy to MPImage
        val bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees.toFloat())
        val mpImage = BitmapImageBuilder(bitmap).build()

        try {
            val result = landmarker.detect(mpImage)
            processResult(result)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun processResult(result: HandLandmarkerResult) {
        val landmarks = result.landmarks()
        
        if (landmarks.isNotEmpty()) {
            val hand = landmarks[0]
            val wrist = hand[0] // Landmark index 0 is the Wrist
            
            // Detection Logic:
            // 1. "Raised Hand" = Wrist in the top 35% of the frame (Y < 0.35)
            // 2. Bounding Box check: Approximate hand size by distance between Wrist and Middle Finger Tip (8)
            val middleFingerTip = hand[12] // Middle Finger Tip
            val handSize = Math.sqrt(
                Math.pow((wrist.x() - middleFingerTip.x()).toDouble(), 2.0) +
                Math.pow((wrist.y() - middleFingerTip.y()).toDouble(), 2.0)
            )

            // Challenge 2: The "False Positive" Problem (Proximity and Vertical Threshold)
            val isRaised = wrist.y() < 0.35f
            val isCloseEnough = handSize > 0.15 // Hand must occupy enough space
            val isCentered = wrist.x() in 0.2f..0.8f

            if (isRaised && isCloseEnough && isCentered) {
                consecutiveDetections++
                if (currentState !is State.HandDetected) {
                    currentState = State.HandDetected
                }
                
                if (consecutiveDetections >= requiredConsecutiveFrames) {
                    triggerGesture()
                }
            } else {
                consecutiveDetections = 0
                if (currentState is State.HandDetected) {
                    currentState = State.Searching
                }
            }
        } else {
            consecutiveDetections = 0
        }
    }

    private fun triggerGesture() {
        if (currentState is State.Searching) {
            currentState = State.Cooldown
            lastTriggerTime = System.currentTimeMillis()
            consecutiveDetections = 0
            onGestureTriggered()
        }
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        if (degrees == 0f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    fun close() {
        backgroundExecutor.execute {
            handLandmarker?.close()
            handLandmarker = null
        }
        backgroundExecutor.shutdown()
    }
}
