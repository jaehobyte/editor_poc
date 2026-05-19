package com.example.photorecipe.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/**
 * Decode a content URI to a software Bitmap that respects EXIF orientation.
 *
 * `BitmapFactory.decodeStream` returns raw sensor pixels — phone cameras save
 * everything landscape and put rotation in EXIF, which BitmapFactory ignores.
 * `ImageDecoder` honors that metadata so portrait photos arrive upright.
 *
 * `ALLOCATOR_SOFTWARE` + `isMutableRequired=false` keeps the bitmap on the
 * Java heap so we can call `getPixels` / `setPixels` for the CPU recipe path.
 */
fun decodeBitmapWithOrientation(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}
