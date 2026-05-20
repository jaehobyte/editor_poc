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
     * 편집 합성용 페더된 알파 마스크 — 경계가 Gaussian 으로 부드럽게 fade out.
     * GL 셰이더 `mix(global, masked, a)` 의 `a` 가 0/1 하드 컷이면 보정 폭이 클 때
     * 마스크 경계선이 그대로 드러나는 문제를 해소.
     * UI 의 dim/boundary 오버레이는 일부러 [alphaBitmap] (샤프) 을 그대로 사용 —
     * "여기까지가 마스크" 를 시각적으로 명확하게 보여주기 위해서.
     */
    val featheredAlphaBitmap: Bitmap by lazy { computeFeathered(alphaBitmap) }

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

/**
 * 분리형 가우시안 블러로 마스크 알파를 페더링.
 *
 * 비싼 큰 마스크는 1024 max-side 로 일단 다운스케일해서 blur → 다시 원본 크기로
 * 업스케일. GL_LINEAR 텍스처 보간이 추가로 부드럽게 만들어주므로 시각적 손실은
 * 무시 가능.
 */
private fun computeFeathered(maskBitmap: Bitmap): Bitmap {
    val origW = maskBitmap.width
    val origH = maskBitmap.height
    val maxSide = maxOf(origW, origH)
    val workScale = if (maxSide > BLUR_WORK_MAX_SIDE) BLUR_WORK_MAX_SIDE.toFloat() / maxSide else 1f
    val workW = (origW * workScale).toInt().coerceAtLeast(1)
    val workH = (origH * workScale).toInt().coerceAtLeast(1)
    val work = if (workScale < 1f) {
        Bitmap.createScaledBitmap(maskBitmap, workW, workH, true)
    } else {
        maskBitmap
    }

    val srcPx = IntArray(workW * workH)
    work.getPixels(srcPx, 0, workW, 0, 0, workW, workH)
    if (work !== maskBitmap) work.recycle() // 스케일 임시 비트맵은 더 이상 필요 없음.
    val srcA = FloatArray(workW * workH) { i -> ((srcPx[i] ushr 24) and 0xFF).toFloat() }

    // 마스크 짧은 변의 약 1.5% 를 sigma 로. 너무 작으면 안 보이고, 너무 크면 느려진다.
    val sigma = (minOf(workW, workH) * 0.015f).coerceIn(4f, 16f)
    val radius = (3f * sigma).toInt().coerceAtLeast(1)
    val kernel = FloatArray(2 * radius + 1)
    val twoSigmaSq = 2f * sigma * sigma
    var sum = 0f
    for (i in kernel.indices) {
        val x = (i - radius).toFloat()
        kernel[i] = kotlin.math.exp(-x * x / twoSigmaSq)
        sum += kernel[i]
    }
    for (i in kernel.indices) kernel[i] /= sum

    // Horizontal pass
    val tmp = FloatArray(workW * workH)
    for (y in 0 until workH) {
        val row = y * workW
        for (x in 0 until workW) {
            val k0 = maxOf(-radius, -x)
            val k1 = minOf(radius, workW - 1 - x)
            var v = 0f
            for (k in k0..k1) v += kernel[k + radius] * srcA[row + x + k]
            tmp[row + x] = v
        }
    }

    // Vertical pass → packed ARGB ints (white RGB, blurred A)
    val outPx = IntArray(workW * workH)
    for (y in 0 until workH) {
        val k0 = maxOf(-radius, -y)
        val k1 = minOf(radius, workH - 1 - y)
        for (x in 0 until workW) {
            var v = 0f
            for (k in k0..k1) v += kernel[k + radius] * tmp[(y + k) * workW + x]
            val a = v.toInt().coerceIn(0, 255)
            outPx[y * workW + x] = (a shl 24) or 0x00FFFFFF
        }
    }
    val blurred = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
    blurred.setPixels(outPx, 0, workW, 0, 0, workW, workH)

    return if (workScale < 1f) {
        val upscaled = Bitmap.createScaledBitmap(blurred, origW, origH, true)
        if (upscaled !== blurred) blurred.recycle()
        upscaled
    } else {
        blurred
    }
}

private const val BLUR_WORK_MAX_SIDE = 1024
