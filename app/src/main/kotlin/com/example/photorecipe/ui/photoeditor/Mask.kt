package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import com.example.photorecipe.EditorParams

/**
 * 한 개의 영역 마스크 + 그 영역에 적용할 보정 파라미터.
 *
 * @param alphaBitmap mask intensity 를 ARGB_8888 의 ALPHA 채널에 담은 비트맵.
 *                    RGB 는 흰색 (255). GL 셰이더가 `.a` 로 마스크 강도 읽고,
 *                    Compose UI 가 ColorFilter / inverted-dim 오버레이에 활용.
 */
@Stable
class Mask(
    val id: String,
    val alphaBitmap: Bitmap,
    val params: EditorParams = EditorParams(),
    val label: String = id,
) {
    /**
     * "Lightroom 식 활성 마스크 포커스" 오버레이: 마스크가 강한 곳일수록 투명하고,
     * 마스크 바깥(원본 영역) 은 검정으로 어두워진다.
     */
    val dimOverlayBitmap: Bitmap by lazy { computeInvertedDim(alphaBitmap) }

    /** 마스크 경계선 비트맵 — 알파 그라디언트가 큰 픽셀만 불투명 흰색. 나머지는 투명. */
    val boundaryBitmap: Bitmap by lazy { computeBoundary(alphaBitmap) }
}

/**
 * @param dimStrength 마스크 바깥의 최대 어둡기 (0..1). 0.6 = 약 60% 검정 오버레이.
 */
private fun computeInvertedDim(maskBitmap: Bitmap, dimStrength: Float = 0.6f): Bitmap {
    val w = maskBitmap.width
    val h = maskBitmap.height
    val src = IntArray(w * h)
    maskBitmap.getPixels(src, 0, w, 0, 0, w, h)
    val dst = IntArray(w * h)
    val maxDim = (dimStrength.coerceIn(0f, 1f) * 255f).toInt()
    for (i in src.indices) {
        val a = (src[i] ushr 24) and 0xFF
        val invA = ((255 - a) * maxDim) / 255
        // ARGB: RGB = 0 (black), alpha = invA
        dst[i] = invA shl 24
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(dst, 0, w, 0, 0, w, h)
    return out
}

/**
 * 4-neighbor 알파 그라디언트가 [threshold] 보다 큰 픽셀을 불투명 흰색으로 표시.
 * 결과: 마스크 경계선만 보이는 비트맵 (나머지 모두 투명).
 */
private fun computeBoundary(maskBitmap: Bitmap, threshold: Int = 30): Bitmap {
    val w = maskBitmap.width
    val h = maskBitmap.height
    val src = IntArray(w * h)
    maskBitmap.getPixels(src, 0, w, 0, 0, w, h)
    val dst = IntArray(w * h)
    val white = 0xFFFFFFFF.toInt()
    for (y in 1 until h - 1) {
        val row = y * w
        for (x in 1 until w - 1) {
            val i = row + x
            val l = (src[i - 1] ushr 24) and 0xFF
            val r = (src[i + 1] ushr 24) and 0xFF
            val u = (src[i - w] ushr 24) and 0xFF
            val d = (src[i + w] ushr 24) and 0xFF
            val maxN = maxOf(maxOf(l, r), maxOf(u, d))
            val minN = minOf(minOf(l, r), minOf(u, d))
            if (maxN - minN > threshold) {
                dst[i] = white
            }
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(dst, 0, w, 0, 0, w, h)
    return out
}
