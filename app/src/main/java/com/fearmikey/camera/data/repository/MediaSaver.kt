package com.fearmikey.camera.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single place that writes processed images to MediaStore (DCIM/Camera).
 * Files are inserted as pending so galleries never see half-written JPEGs, and
 * basic EXIF (date, camera model, optional GPS) is written afterwards.
 */
class MediaSaver(private val context: Context) {

    fun saveJpeg(bitmap: Bitmap, prefix: String, location: Location? = null, quality: Int = 95): Uri? {
        val now = System.currentTimeMillis()
        val name = "${prefix}_" + FILE_DATE.format(Date(now))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "wt")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IllegalStateException("JPEG compression failed")
                }
            } ?: throw IllegalStateException("Unable to open output stream")
            writeExif(uri, now, location)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $name", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Builds MediaStore values for a new photo written by CameraX itself. */
    fun newPhotoValues(prefix: String = "IMG"): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_" + FILE_DATE.format(Date()))
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    /** Builds MediaStore values for a new video. */
    fun newVideoValues(prefix: String = "VID"): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_" + FILE_DATE.format(Date()))
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    private fun writeExif(uri: Uri, timestamp: Long, location: Location?) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val date = EXIF_DATE.format(Date(timestamp))
                exif.setAttribute(ExifInterface.TAG_DATETIME, date)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                if (location != null) writeGps(exif, location)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to write EXIF: ${e.message}")
        }
    }

    private fun writeGps(exif: ExifInterface, location: Location) {
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(location.latitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (location.latitude >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(location.longitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (location.longitude >= 0) "E" else "W")
        if (location.hasAltitude()) {
            val alt = location.altitude
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${(kotlin.math.abs(alt) * 100).toLong()}/100")
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt >= 0) "0" else "1")
        }
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = location.time
        }
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, GPS_DATE.format(utc.time))
        exif.setAttribute(
            ExifInterface.TAG_GPS_TIMESTAMP,
            "${utc.get(java.util.Calendar.HOUR_OF_DAY)}/1,${utc.get(java.util.Calendar.MINUTE)}/1,${utc.get(java.util.Calendar.SECOND)}/1"
        )
    }

    /** Decimal degrees to the EXIF rational "deg/1,min/1,sec*1000/1000" form. */
    private fun toDms(value: Double): String {
        var v = kotlin.math.abs(value)
        val deg = v.toInt()
        v = (v - deg) * 60
        val min = v.toInt()
        val sec = ((v - min) * 60 * 1000).toLong()
        return "$deg/1,$min/1,$sec/1000"
    }

    companion object {
        private const val TAG = "MediaSaver"
        private val GPS_DATE = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        const val RELATIVE_PATH = "DCIM/Camera"
        private val FILE_DATE = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val EXIF_DATE = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}
