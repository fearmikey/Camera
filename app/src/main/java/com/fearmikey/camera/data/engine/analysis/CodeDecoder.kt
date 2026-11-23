package com.fearmikey.camera.data.engine.analysis

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.fearmikey.camera.data.engine.processing.NativeVisionBridge
import com.fearmikey.camera.domain.model.CodeFormat
import com.fearmikey.camera.domain.model.ScannedCode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * On-device QR code + EAN/UPC barcode decoding (OpenCV objdetect via
 * [NativeVisionBridge.nativeDecodeCodes]). Blocking: call off the main thread.
 */
object CodeDecoder {
    private const val TAG = "CodeDecoder"
    private const val MAX_CODES = 8
    private const val IMAGE_MAX_SIDE = 2048
    private const val IMAGE_FALLBACK_SIDE = 1024

    /** Decodes an ARGB_8888 [bitmap]. Corners are normalized to the bitmap. */
    fun decode(bitmap: Bitmap, tryHarder: Boolean = false): List<ScannedCode> {
        val corners = FloatArray(MAX_CODES * 8)
        val raw = try {
            NativeVisionBridge.nativeDecodeCodes(bitmap, corners, tryHarder)
        } catch (e: Exception) {
            Log.w(TAG, "Decode error: ${e.message}")
            null
        } ?: return emptyList()
        return (0 until raw.size / 2).mapNotNull { i ->
            val text = decodeText(raw[i * 2 + 1]).trimEnd('\u0000')
            if (text.isBlank()) return@mapNotNull null
            ScannedCode(
                text = text,
                format = CodeFormat.fromNative(String(raw[i * 2], Charsets.US_ASCII)),
                corners = corners.copyOfRange(i * 8, i * 8 + 8).toList(),
            )
        }
    }

    /**
     * Decodes codes in a picked image (screenshot, photo). Tries a large size first for small
     * codes, then a smaller one, which helps when a code fills the frame at high resolution.
     */
    fun decodeImage(resolver: ContentResolver, uri: Uri): List<ScannedCode> {
        val bitmap = try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > IMAGE_MAX_SIDE) {
                    val scale = IMAGE_MAX_SIDE.toFloat() / longSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }.toArgb8888()
        } catch (e: Exception) {
            Log.w(TAG, "Could not load image $uri: ${e.message}")
            return emptyList()
        }
        try {
            decode(bitmap, tryHarder = true).let { if (it.isNotEmpty()) return it }
            val longSide = maxOf(bitmap.width, bitmap.height)
            if (longSide <= IMAGE_FALLBACK_SIDE) return emptyList()
            val scale = IMAGE_FALLBACK_SIDE.toFloat() / longSide
            val small = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
            return try {
                decode(small, tryHarder = true)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** QR payloads are usually UTF-8 but the spec default is ISO-8859-1; fall back when not valid UTF-8. */
    internal fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }

    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        val copy = copy(Bitmap.Config.ARGB_8888, false)
        recycle()
        return copy
    }
}
