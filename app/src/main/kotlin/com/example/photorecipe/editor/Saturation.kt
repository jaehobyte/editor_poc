package com.example.photorecipe.editor

/**
 * Galaxy `libphotoeditorEngine.so` Saturation — fixed-point RGB mixing matrix.
 *
 * 출력은 GLSL `mat3` (column-major) 으로 바로 업로드할 수 있는 9-element FloatArray.
 *
 *   internal  = ui + 100
 *   s32Weight = clamp((internal*103 − 10300)*0.1 + 1024, 0, 2048).toInt()
 *   s32Gain   = 1024 − s32Weight
 *   R_gain    = (s32Gain * 316) >> 10
 *   G_gain    = (s32Gain * 624) >> 10
 *   B_gain    = (s32Gain *  84) >> 10
 *
 *   M[i,j] = ( (s32Weight if i==j else 0) + chan_gain[j] ) / 1024
 *
 * ui = 0 → identity. ui = -100 → 모든 행이 (316,624,84)/1024 → 그레이스케일.
 * 각 행의 합은 항상 1 → 무채색 픽셀은 항상 보존.
 *
 * !! 캘리브레이션 상수 변경 금지.
 */
fun saturationMatrix(ui: Float): FloatArray {
    val u = ui.coerceIn(-100f, 100f)
    val internal = u + 100f
    val s32WeightF = ((internal * 103f - 10300f) * 0.1f + 1024f).coerceIn(0f, 2048f)
    val s32Weight = s32WeightF.toInt()
    val s32Gain = 1024 - s32Weight
    val rGain = (s32Gain * 316) shr 10
    val gGain = (s32Gain * 624) shr 10
    val bGain = (s32Gain * 84) shr 10

    val inv = 1f / 1024f
    val rGf = rGain.toFloat()
    val gGf = gGain.toFloat()
    val bGf = bGain.toFloat()
    val wF = s32Weight.toFloat()

    // Column-major: column j 는 j 번째 입력 채널에 대한 출력 기여도.
    return floatArrayOf(
        // Column 0 (R input)
        (wF + rGf) * inv, rGf * inv,          rGf * inv,
        // Column 1 (G input)
        gGf * inv,        (wF + gGf) * inv,   gGf * inv,
        // Column 2 (B input)
        bGf * inv,        bGf * inv,          (wF + bGf) * inv,
    )
}
