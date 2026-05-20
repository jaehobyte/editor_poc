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
     * 마스크 바깥(원본 영역) 은 검정으로 어두워진다. 사용자가 어떤 영역이 편집 대상인지
     * 한눈에 알 수 있고, 정작 마스크 안의 보정 결과는 가리지 않는다.
     *
     * 첫 사용 시 한 번만 계산되고 캐시됨 (mask 비트맵과 같은 픽셀 수 메모리).
     */
    val dimOverlayBitmap: Bitmap by lazy { computeInvertedDim(alphaBitmap) }
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
