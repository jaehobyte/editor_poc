package com.example.photorecipe.editor

import android.graphics.Bitmap
import com.example.photorecipe.EditorParams
import kotlin.math.round

/**
 * GPU `EFFECTS_FRAG` 와 동일한 9-단계 파이프라인의 CPU 구현.
 * 풀해상도 비트맵 저장(export) 용 — `gl/ImageRenderer` 가 화면용 미리보기를
 * 처리한다면, 이 함수는 디스크에 저장될 최종 결과물을 만든다.
 */
fun applyRecipe(input: Bitmap, p: EditorParams): Bitmap {
    val w = input.width
    val h = input.height
    val n = w * h
    val pixels = IntArray(n)
    input.getPixels(pixels, 0, w, 0, 0, w, h)

    // 효과별 상수 1회 계산
    val wb = wbMultipliers(p.temperature)
    val curve = contrastCurve(p.contrast)
    val satM = saturationMatrix(p.saturation)
    val lut = if (p.colorTuningEnabled) buildColorTuningLut(p.colorTuning) else null

    val tmpRgb = FloatArray(3)
    val tmpHsl = FloatArray(3)

    for (i in 0 until n) {
        val px = pixels[i]
        var r = ((px ushr 16) and 0xFF) / 255f
        var g = ((px ushr 8) and 0xFF) / 255f
        var b = (px and 0xFF) / 255f

        // 1. Temperature
        r = (r * wb[0]).coerceIn(0f, 1f)
        g = (g * wb[1]).coerceIn(0f, 1f)
        b = (b * wb[2]).coerceIn(0f, 1f)

        // 2. Contrast
        r = applyContrast(r, curve)
        g = applyContrast(g, curve)
        b = applyContrast(b, curve)

        // 3. Tint
        val t = applyTint(r, g, b, p.tint)
        r = t[0]; g = t[1]; b = t[2]

        // 4. Saturation (column-major mat3)
        val rn = (satM[0]*r + satM[3]*g + satM[6]*b).coerceIn(0f, 1f)
        val gn = (satM[1]*r + satM[4]*g + satM[7]*b).coerceIn(0f, 1f)
        val bn = (satM[2]*r + satM[5]*g + satM[8]*b).coerceIn(0f, 1f)
        r = rn; g = gn; b = bn

        // 5-8. Luma effects on Y
        val ycc = rgbToYCbCr(r, g, b)
        var y = ycc[0]
        y = applyBrightness(y, p.brightness)
        y = applyExposure(y, p.exposure)
        y = applyHighlights(y, p.highlights)
        y = applyShadows(y, p.shadows)
        val rgb = yCbCrToRgb(y, ycc[1], ycc[2])
        r = rgb[0].coerceIn(0f, 1f)
        g = rgb[1].coerceIn(0f, 1f)
        b = rgb[2].coerceIn(0f, 1f)

        // 9. Color tuning
        if (lut != null) {
            rgbToHsl(r, g, b, tmpHsl)
            val idx = round(tmpHsl[0]).toInt().coerceIn(0, 360)
            val dH = lut[idx * 4]
            val dS = lut[idx * 4 + 1]
            val dL = lut[idx * 4 + 2]
            var hN = (tmpHsl[0] + dH) % 360f
            if (hN < 0f) hN += 360f
            val sN = (tmpHsl[1] + dS).coerceIn(0f, 1f)
            val lN = (tmpHsl[2] + dL).coerceIn(0f, 1f)
            hslToRgb(hN, sN, lN, tmpRgb)
            r = tmpRgb[0].coerceIn(0f, 1f)
            g = tmpRgb[1].coerceIn(0f, 1f)
            b = tmpRgb[2].coerceIn(0f, 1f)
        }

        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}
