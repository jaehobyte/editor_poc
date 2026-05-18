package com.example.photorecipe.editor

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.round

/**
 * galaxy_editor_python.py의 1:1 Kotlin 포팅.
 *
 * 동작 도메인은 모두 [0, 255] float (Python NumPy 코드와 동일).
 * params 는 모델 출력 그대로 [-1, 1] (29개) — 내부에서 ×100 으로 UI 스케일로 변환.
 *
 * Pipeline (C 코드 순서):
 *   1. Temperature  – 8-point 캘리브레이션 LUT 보간
 *   2. Contrast     – 256-entry quadratic LUT
 *   3. Tint         – 휘도 가중 G ↔ magenta
 *   4. Saturation   – fixed-point RGB mixing matrix
 *   5. Brightness   ┐
 *   6. Exposure     │  YCbCr 의 Y 도메인
 *   7. Highlights   │
 *   8. Shadows      ┘
 *   9. Color tuning – 7 hue × HSL shift (361-entry LUT)
 */
object GalaxyEditor {

    /**
     * [bitmap]에 [params](29-element, [-1,1])을 적용하고 새 비트맵을 반환.
     * 입력 비트맵은 변경되지 않음.
     */
    fun apply(bitmap: Bitmap, params: FloatArray): Bitmap {
        require(params.size == GalaxyConstants.NUM_PARAMS) {
            "params must have ${GalaxyConstants.NUM_PARAMS} entries, got ${params.size}"
        }

        val w = bitmap.width
        val h = bitmap.height
        val n = w * h

        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // ARGB → 3 개의 [0,255] FloatArray 로 분리
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val px = pixels[i]
            r[i] = ((px ushr 16) and 0xFF).toFloat()
            g[i] = ((px ushr 8) and 0xFF).toFloat()
            b[i] = (px and 0xFF).toFloat()
        }

        // ── Tone (1-4) ────────────────────────────────────────────
        val temperature = params[0] * 100f
        val contrast    = params[1] * 100f
        val tint        = params[2] * 100f
        val saturation  = params[3] * 100f
        val brightness  = params[4] * 100f
        val exposure    = params[5] * 100f
        val highlights  = params[6] * 100f
        val shadows     = params[7] * 100f

        if (temperature != 0f) applyTemperature(r, g, b, temperature)
        if (contrast    != 0f) applyContrast(r, g, b, contrast)
        if (tint        != 0f) applyTint(r, g, b, tint)
        if (saturation  != 0f) applySaturation(r, g, b, saturation)

        if (brightness != 0f || exposure != 0f || highlights != 0f || shadows != 0f) {
            applyLumaEffects(r, g, b, brightness, exposure, highlights, shadows)
        }

        // ── Color tuning (5) ──────────────────────────────────────
        // params[8..28] 중 하나라도 0 이 아니면 적용
        var anyColor = false
        for (i in 8 until 29) if (params[i] != 0f) { anyColor = true; break }
        if (anyColor) applyColorTuning(r, g, b, params)

        // 다시 ARGB 패킹
        for (i in 0 until n) {
            val ri = r[i].coerceIn(0f, 255f).toInt()
            val gi = g[i].coerceIn(0f, 255f).toInt()
            val bi = b[i].coerceIn(0f, 255f).toInt()
            pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ════════════════════════════════════════════════════════════════
    //  Tone (galaxy_editor_python.py:166-409)
    // ════════════════════════════════════════════════════════════════

    /** Temperature: 8-point WB LUT 보간 후 채널별 곱셈. */
    private fun applyTemperature(r: FloatArray, g: FloatArray, b: FloatArray, ui: Float) {
        val u = ui.coerceIn(-100f, 100f)
        val wbR = interp(u, GalaxyConstants.WB_UI, GalaxyConstants.WB_R)
        val wbG = interp(u, GalaxyConstants.WB_UI, GalaxyConstants.WB_G)
        val wbB = interp(u, GalaxyConstants.WB_UI, GalaxyConstants.WB_B)
        for (i in r.indices) {
            r[i] = (r[i] * wbR).coerceIn(0f, 255f)
            g[i] = (g[i] * wbG).coerceIn(0f, 255f)
            b[i] = (b[i] * wbB).coerceIn(0f, 255f)
        }
    }

    /** Contrast: 256-entry quadratic LUT. */
    private fun applyContrast(r: FloatArray, g: FloatArray, b: FloatArray, ui: Float) {
        // internal = ui + 100, fv = (internal+300)/400, curve = fv*fv
        val internal = ui + 100f
        val fv = (internal + 300f) / 400f
        val curve = fv * fv

        val lut = FloatArray(256)
        for (i in 0..255) {
            val v = (0.5f + curve * (i / 255f - 0.5f)) * 255f
            lut[i] = v.coerceIn(0f, 255f)
        }

        for (i in r.indices) {
            r[i] = lut[r[i].coerceIn(0f, 255f).toInt()]
            g[i] = lut[g[i].coerceIn(0f, 255f).toInt()]
            b[i] = lut[b[i].coerceIn(0f, 255f).toInt()]
        }
    }

    /** Tint: 휘도 가중 green ↔ magenta. */
    private fun applyTint(r: FloatArray, g: FloatArray, b: FloatArray, tint: Float) {
        for (i in r.indices) {
            val ri = r[i]; val gi = g[i]; val bi = b[i]
            val L = 0.2126f * ri + 0.7152f * gi + 0.0722f * bi
            val fVar = if (L < 128f) {
                val t = L / 170f
                t * t
            } else {
                val t = (255f - L) / 170f
                t * t
            }
            if (tint < 0f) {
                // 그린 부스트
                val nt = -tint
                g[i] = (gi + fVar * 0.0034f * nt * gi).coerceIn(0f, 255f)
            } else {
                // 마젠타 (R + B 부스트)
                r[i] = (ri + fVar * 0.0032f * tint * ri).coerceIn(0f, 255f)
                b[i] = (bi + fVar * 0.0034f * tint * bi).coerceIn(0f, 255f)
                // 매우 어두운 픽셀 (L < 51) 만 미세한 G 시프트
                val gNew = if (L < 51f) gi + fVar * 0.0005f * tint * gi else gi
                g[i] = gNew.coerceIn(0f, 255f)
            }
        }
    }

    /** Saturation: fixed-point RGB mixing matrix. */
    private fun applySaturation(r: FloatArray, g: FloatArray, b: FloatArray, ui: Float) {
        val internal = ui + 100f
        val s32WeightF = ((internal * 103f - 10300f) * 0.1f + 1024f).coerceIn(0f, 2048f)
        val s32Weight = s32WeightF.toInt()
        val s32Gain = 1024 - s32Weight
        val rGain = (s32Gain * 316) shr 10
        val gGain = (s32Gain * 624) shr 10
        val bGain = (s32Gain *  84) shr 10

        val w = s32Weight.toFloat()
        val rg = rGain.toFloat()
        val gg = gGain.toFloat()
        val bg = bGain.toFloat()

        for (i in r.indices) {
            val ri = r[i]; val gi = g[i]; val bi = b[i]
            val rNew = (gi * gg + (w + rg) * ri + bi * bg) / 1024f
            val gNew = (ri * rg + (gg + w) * gi + bi * bg) / 1024f
            val bNew = (gi * gg +  ri * rg + (bg + w) * bi) / 1024f
            r[i] = rNew.coerceIn(0f, 255f)
            g[i] = gNew.coerceIn(0f, 255f)
            b[i] = bNew.coerceIn(0f, 255f)
        }
    }

    /** Brightness / Exposure / Highlights / Shadows — 모두 YCbCr 의 Y 위에서. */
    private fun applyLumaEffects(
        r: FloatArray, g: FloatArray, b: FloatArray,
        brightness: Float, exposure: Float, highlights: Float, shadows: Float,
    ) {
        val w = 0.7999f
        for (i in r.indices) {
            val ri = r[i]; val gi = g[i]; val bi = b[i]

            // RGB → YCbCr (BT.601, fixed-point ÷256)
            var Y = (77f * ri + 150f * gi + 29f * bi) / 256f
            val Cb = (-43f * ri - 85f * gi + 128f * bi) / 256f
            val Cr = (128f * ri - 107f * gi - 21f * bi) / 256f

            // Brightness
            if (brightness != 0f) {
                Y = if (Y < 128f) {
                    (w * brightness + 127f) * Y / 127f
                } else {
                    w * brightness * (255f - Y) / 127f + Y
                }
                if (Y < 0f) Y = 0f else if (Y > 255f) Y = 255f
            }

            // Exposure: quadratic flat offset
            if (exposure != 0f) {
                Y += exposure * kotlin.math.abs(exposure) / 100f
                if (Y < 0f) Y = 0f else if (Y > 255f) Y = 255f
            }

            // Highlights
            if (highlights != 0f) {
                Y += 0.00276f * highlights * Y * (Y / 200f - 0.4978f)
                if (Y < 0f) Y = 0f else if (Y > 255f) Y = 255f
            }

            // Shadows
            if (shadows != 0f) {
                if (shadows < 0f) {
                    if (Y <= 159.4f) {
                        var Ysh = Y + 0.00301f * (159.4f - Y) * shadows
                        if (Ysh < 0f) Ysh = 0f else if (Ysh > 255f) Ysh = 255f
                        Y = Ysh
                    }
                } else {
                    val Ysh = if (Y <= 128f) Y + 0.28f * shadows
                              else Y + 0.0022f * (255f - Y) * shadows
                    Y = if (Ysh < 0f) 0f else if (Ysh > 255f) 255f else Ysh
                }
            }

            // YCbCr → RGB
            val rNew = Y + Cr * (359f / 256f)
            val gNew = Y - (Cb * 88f + Cr * 183f) / 256f
            val bNew = Y + Cb * (454f / 256f)

            r[i] = rNew.coerceIn(0f, 255f)
            g[i] = gNew.coerceIn(0f, 255f)
            b[i] = bNew.coerceIn(0f, 255f)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Color tuning (galaxy_editor_python.py:203-244, 413-444)
    // ════════════════════════════════════════════════════════════════

    /** 7 색상 × (hue/sat/lum) HSL shift. */
    private fun applyColorTuning(r: FloatArray, g: FloatArray, b: FloatArray, params: FloatArray) {
        // 361-entry LUT 빌드 (per-degree shift)
        val hLut = FloatArray(361)
        val sLut = FloatArray(361)
        val lLut = FloatArray(361)

        val sigma2H = 2f * GalaxyConstants.COLOR_SIGMA_HUE * GalaxyConstants.COLOR_SIGMA_HUE
        val sigma2S = 2f * GalaxyConstants.COLOR_SIGMA_SAT * GalaxyConstants.COLOR_SIGMA_SAT
        val sigma2L = 2f * GalaxyConstants.COLOR_SIGMA_LUM * GalaxyConstants.COLOR_SIGMA_LUM

        for (ci in 0 until 7) {
            val base = 8 + ci * 3
            val pHue = params[base]
            val pSat = params[base + 1]
            val pLum = params[base + 2]

            // params[*] ∈ [-1, 1] → ui ∈ [-100, 100]
            // ui * gain = 실제 shift 양
            val hVal = pHue * 100f * GalaxyConstants.COLOR_GAIN_HUE         // degrees
            val sVal = pSat * 100f * GalaxyConstants.COLOR_GAIN_SAT         // fraction (S ∈ [0,1])
            val lVal = pLum * 100f * GalaxyConstants.COLOR_GAIN_LUM         // fraction (L ∈ [0,1])
            if (hVal == 0f && sVal == 0f && lVal == 0f) continue

            val center = GalaxyConstants.COLOR_CENTERS[ci]
            for (deg in 0..360) {
                // 원형 거리 ∈ [-180, 180]
                var d = ((deg - center + 180f) % 360f) - 180f
                if (d < -180f) d += 360f
                val d2 = d * d
                if (hVal != 0f) hLut[deg] += exp(-d2 / sigma2H) * hVal
                if (sVal != 0f) sLut[deg] += exp(-d2 / sigma2S) * sVal
                if (lVal != 0f) lLut[deg] += exp(-d2 / sigma2L) * lVal
            }
        }

        val tmp = FloatArray(3)
        for (i in r.indices) {
            // RGB([0,1]) → HLS
            ColorSpaces.rgbToHls(r[i] / 255f, g[i] / 255f, b[i] / 255f, tmp)
            val H = tmp[0]
            val L = tmp[1]
            val S = tmp[2]

            val idx = round(H).toInt().coerceIn(0, 360)
            val dH = hLut[idx]
            val dS = sLut[idx]
            val dL = lLut[idx]

            var Hn = (H + dH) % 360f
            if (Hn < 0f) Hn += 360f
            val Ln = (L + dL).coerceIn(0f, 1f)
            val Sn = (S + dS).coerceIn(0f, 1f)

            ColorSpaces.hlsToRgb(Hn, Ln, Sn, tmp)
            r[i] = (tmp[0] * 255f).coerceIn(0f, 255f)
            g[i] = (tmp[1] * 255f).coerceIn(0f, 255f)
            b[i] = (tmp[2] * 255f).coerceIn(0f, 255f)
        }
    }

    // ── 1D 선형 보간 (np.interp 와 동일) ─────────────────────────────
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[xs.size - 1]
        var i = 0
        while (i < xs.size - 1 && xs[i + 1] < x) i++
        val x0 = xs[i]; val x1 = xs[i + 1]
        val y0 = ys[i]; val y1 = ys[i + 1]
        val t = (x - x0) / (x1 - x0)
        return y0 + t * (y1 - y0)
    }
}