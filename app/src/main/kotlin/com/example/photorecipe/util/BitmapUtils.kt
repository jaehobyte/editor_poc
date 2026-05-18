package com.example.photorecipe.util

import android.graphics.Bitmap

/**
 * 종횡비를 유지하면서 긴 변이 [maxDim] 이하가 되도록 (newWidth, newHeight) 반환.
 * 이미 범위 안이면 입력 그대로.
 */
fun scaledDimensions(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "dimensions must be positive (got ${width}x${height})" }
    require(maxDim > 0) { "maxDim must be positive (got $maxDim)" }
    val maxSide = maxOf(width, height)
    if (maxSide <= maxDim) return width to height
    val scale = maxDim.toDouble() / maxSide
    return (width * scale).toInt() to (height * scale).toInt()
}

/** GL 텍스처 업로드를 위한 안전한 크기로 다운샘플 (긴 변 [maxDim] 이하). */
fun downscaleForGL(bitmap: Bitmap, maxDim: Int = 2048): Bitmap {
    val (w, h) = scaledDimensions(bitmap.width, bitmap.height, maxDim)
    if (w == bitmap.width && h == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}
