package com.example.photorecipe.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save [bitmap] as JPEG into the device gallery under `Pictures/PhotoRecipe/`.
 * API 31+ uses scoped storage via MediaStore — no permission required.
 */
fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayName: String = defaultName(),
    quality: Int = 95,
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhotoRecipe")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("MediaStore insert returned null")

    resolver.openOutputStream(uri)?.use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
            error("Bitmap.compress failed")
        }
    } ?: error("openOutputStream returned null")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return uri
}

private fun defaultName(): String {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "PhotoRecipe_$ts"
}
