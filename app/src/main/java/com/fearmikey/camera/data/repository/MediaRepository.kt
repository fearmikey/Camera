package com.fearmikey.camera.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

class MediaRepository(private val context: Context) {

    fun getLatestMediaUri(): Uri? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED
        )
        
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        
        // Query Images
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imageCursor = context.contentResolver.query(
            imageUri,
            projection,
            null,
            null,
            sortOrder
        )

        var latestImageUri: Uri? = null
        var latestImageDate: Long = -1
        
        imageCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val id = cursor.getLong(idColumn)
                latestImageDate = cursor.getLong(dateColumn)
                latestImageUri = ContentUris.withAppendedId(imageUri, id)
            }
        }

        // Query Videos
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoCursor = context.contentResolver.query(
            videoUri,
            projection,
            null,
            null,
            sortOrder
        )

        var latestVideoUri: Uri? = null
        var latestVideoDate: Long = -1

        videoCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val id = cursor.getLong(idColumn)
                latestVideoDate = cursor.getLong(dateColumn)
                latestVideoUri = ContentUris.withAppendedId(videoUri, id)
            }
        }

        return if (latestImageDate > latestVideoDate) latestImageUri else latestVideoUri
    }
}
