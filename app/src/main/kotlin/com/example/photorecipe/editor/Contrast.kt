package com.example.photorecipe.editor

/**
 * Galaxy `libphotoeditorEngine.so` Contrast 곡선 계수.
 *
 * C 코드 (lines 14027-14057):
 *   internal = ui + 100   (0..200)
 *   fv       = (internal + 300) / 400
 *   curve    = fv * fv     (≈ [0.5625, 1.5625])
 *
 * ui = 0 → curve = 1.0 (identity).
 */
fun contrastCurve(ui: Float): Float {
    val u = ui.coerceIn(-100f, 100f)
    val internal = u + 100f
    val fv = (internal + 300f) / 400f
    return fv * fv
}

/**
 * [0, 1] 도메인에서 contrast 곡선 적용.
 *
 *   out = clamp(0.5 + curve * (x - 0.5), 0, 1)
 *
 * curve = 1.0 → no-op. curve > 1 → 중간값에서 양쪽으로 펼침, curve < 1 → 중간값으로 압축.
 */
fun applyContrast(x: Float, curve: Float): Float {
    return (0.5f + curve * (x - 0.5f)).coerceIn(0f, 1f)
}
