package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import android.graphics.Matrix

/** Crop / orientation 상태. ratio = null 이면 자르지 않음(Free). */
data class CropTransform(
    val aspectRatio: Float? = null,
    val rotation: Int = 0,           // 0/90/180/270
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val isIdentity: Boolean
        get() = aspectRatio == null && rotation == 0 && !flipH && !flipV
}

/** UI 에 쓸 비율 프리셋. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    FOUR_FIVE("4:5", 4f / 5f),
    TWO_THREE("2:3", 2f / 3f),
    NINE_SIXTEEN("9:16", 9f / 16f),
    THREE_TWO("3:2", 3f / 2f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    FIVE_FOUR("5:4", 5f / 4f);

    companion object {
        fun fromRatio(r: Float?): AspectPreset = when {
            r == null -> FREE
            else -> entries.firstOrNull { it.ratio != null && kotlin.math.abs(it.ratio - r) < 0.001f }
                ?: FREE
        }
    }
}

/**
 * Crop transform 을 비트맵에 적용 — rotation → flip → centered ratio crop 순.
 */
fun applyCropTransform(src: Bitmap, t: CropTransform): Bitmap {
    if (t.isIdentity) return src
    var result: Bitmap = src

    // 1) Rotation (90° 단위)
    val rotMod = ((t.rotation % 360) + 360) % 360
    if (rotMod != 0) {
        val m = Matrix().apply { postRotate(rotMod.toFloat()) }
        result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, m, true)
    }

    // 2) Flip
    if (t.flipH || t.flipV) {
        val m = Matrix().apply {
            postScale(if (t.flipH) -1f else 1f, if (t.flipV) -1f else 1f)
        }
        result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, m, true)
    }

    // 3) Centered crop to aspect ratio
    val ratio = t.aspectRatio
    if (ratio != null) {
        val curRatio = result.width.toFloat() / result.height
        if (kotlin.math.abs(curRatio - ratio) > 0.001f) {
            if (curRatio > ratio) {
                val newW = (result.height * ratio).toInt().coerceAtLeast(1)
                val x = (result.width - newW) / 2
                result = Bitmap.createBitmap(result, x, 0, newW, result.height)
            } else {
                val newH = (result.width / ratio).toInt().coerceAtLeast(1)
                val y = (result.height - newH) / 2
                result = Bitmap.createBitmap(result, 0, y, result.width, newH)
            }
        }
    }

    return result
}
