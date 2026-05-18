package com.example.photorecipe.editor

import kotlin.math.exp

/**
 * Galaxy 7-색상 × HSL Color Tuning — 361-entry RGBA LUT 빌더.
 *
 * 입력 21개 UI 값 (각 [-100, 100]) 순서:
 *   Red.H, Red.S, Red.L,
 *   Orange.H, Orange.S, Orange.L,
 *   Yellow.H, Yellow.S, Yellow.L,
 *   Green.H, Green.S, Green.L,
 *   Blue.H, Blue.S, Blue.L,
 *   Navy.H, Navy.S, Navy.L,
 *   Purple.H, Purple.S, Purple.L
 *
 * 출력: 361 * 4 = 1444 float array, GL_RGBA32F 1D 텍스처로 직접 업로드 가능.
 * 각 hue degree d ∈ [0, 360] 에 대해 (dH, dS, dL, 0) 4개 float.
 *
 * 각 색상 채널 기여도:
 *   d = circular_distance(hue, center)
 *   weight = exp(-d² / (2σ²))
 *   dH += weight_H · (ui_H * 0.450)             // degrees
 *   dS += weight_S · (ui_S * 0.582 / 100)       // fraction [-1,1]
 *   dL += weight_L · (ui_L * 0.212 / 100)       // fraction [-1,1]
 *
 * !! 캘리브레이션 상수 (centers, sigmas, gains) 변경 금지.
 */

private val COLOR_CENTERS = floatArrayOf(0f, 40f, 60f, 120f, 180f, 240f, 300f)
// σ (galaxy_editor_python.py:153-157 — 실측 캘리브레이션)
private const val COLOR_SIGMA_HUE = 22.2f
private const val COLOR_SIGMA_SAT = 14.3f
private const val COLOR_SIGMA_LUM = 13.6f
// ui=100 일 때의 최대 shift
private const val MAX_HUE_DEG = 45f       // gain = 0.450 deg/unit
private const val MAX_SAT_FRAC = 0.582f   // gain = 0.00582 fraction/unit
private const val MAX_LUM_FRAC = 0.212f   // gain = 0.00212 fraction/unit

private const val NUM_COLORS = 7
private const val NUM_HUE_BINS = 361
private const val LUT_ELEMENTS = NUM_HUE_BINS * 4

fun buildColorTuningLut(params21: FloatArray): FloatArray {
    require(params21.size == 21) {
        "params21 must have 21 elements (got ${params21.size})"
    }

    val out = FloatArray(LUT_ELEMENTS) // all zeros
    val sigma2H = 2f * COLOR_SIGMA_HUE * COLOR_SIGMA_HUE
    val sigma2S = 2f * COLOR_SIGMA_SAT * COLOR_SIGMA_SAT
    val sigma2L = 2f * COLOR_SIGMA_LUM * COLOR_SIGMA_LUM

    for (ci in 0 until NUM_COLORS) {
        val uiH = params21[ci * 3 + 0]
        val uiS = params21[ci * 3 + 1]
        val uiL = params21[ci * 3 + 2]
        val hVal = uiH * 0.450f            // deg
        val sVal = uiS * MAX_SAT_FRAC / 100f
        val lVal = uiL * MAX_LUM_FRAC / 100f
        if (hVal == 0f && sVal == 0f && lVal == 0f) continue

        val center = COLOR_CENTERS[ci]
        for (deg in 0..360) {
            // 원형 거리 [-180, 180] (Kotlin % 부호 대응)
            var rem = (deg.toFloat() - center + 180f) % 360f
            if (rem < 0f) rem += 360f
            val d = rem - 180f
            val d2 = d * d
            val idx = deg * 4
            if (hVal != 0f) out[idx]     += hVal * exp(-(d2 / sigma2H).toDouble()).toFloat()
            if (sVal != 0f) out[idx + 1] += sVal * exp(-(d2 / sigma2S).toDouble()).toFloat()
            if (lVal != 0f) out[idx + 2] += lVal * exp(-(d2 / sigma2L).toDouble()).toFloat()
        }
    }
    return out
}
